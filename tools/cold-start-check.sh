#!/usr/bin/env bash
# FalconX 新环境冷启动基础数据自检（配套 AWS演示档部署手册 §0.6）。
#
# 用法（在 docker compose 环境所在机器上）：
#   bash tools/cold-start-check.sh                    # 默认容器名 falconx-mysql / falconx-clickhouse / falconx-redis
#   MYSQL_CONTAINER=xxx bash tools/cold-start-check.sh
#
# 校验各服务 Flyway/启动初始化后的关键 seed 是否就位（阈值取 2026-06-03 基线，
# 见手册 §0.6 表；symbol 目录扩容后行数只会更多，故用 ≥）。
# 退出码 0=全部通过；非 0=存在 FAIL。
set -uo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-falconx-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
CH_CONTAINER="${CH_CONTAINER:-falconx-clickhouse}"
CH_USER="${CH_USER:-default}"
CH_PASSWORD="${CH_PASSWORD:-falconx}"

FAIL=0

q() { docker exec "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -e "$1" 2>/dev/null | tail -1; }

check_ge() { # label sql threshold
  local v; v=$(q "$2")
  if [ -n "$v" ] && [ "$v" -ge "$3" ] 2>/dev/null; then
    echo "PASS  $1 = $v (>= $3)"
  else
    echo "FAIL  $1 = ${v:-<查询失败>} (期望 >= $3)"; FAIL=1
  fi
}

echo "=== 1. MySQL 5 库存在 ==="
for db in falconx_identity falconx_market falconx_trading falconx_wallet falconx_console; do
  if q "SHOW DATABASES LIKE '$db'" | grep -q "$db"; then echo "PASS  schema $db"; else echo "FAIL  schema $db 缺失"; FAIL=1; fi
done

echo "=== 2. market seed（迁移产出） ==="
check_ge "t_symbol(总目录)"                "SELECT COUNT(*) FROM falconx_market.t_symbol" 1571
check_ge "t_symbol_quote_mapping"          "SELECT COUNT(*) FROM falconx_market.t_symbol_quote_mapping" 1571
check_ge "t_trading_hours(交易时段)"       "SELECT COUNT(*) FROM falconx_market.t_trading_hours" 9000
check_ge "t_symbol_group_markup(default)"  "SELECT COUNT(*) FROM falconx_market.t_symbol_group_markup WHERE group_code='default'" 1571
check_ge "t_symbol_group_visibility"       "SELECT COUNT(*) FROM falconx_market.t_symbol_group_visibility" 1571
# B 切片不变量：mapping 杠杆不得高于 tier1 上限（V19 对齐；跨库只读校验）
MISMATCH=$(q "SELECT COUNT(*) FROM falconx_market.t_symbol_quote_mapping m JOIN falconx_trading.t_symbol_leverage_tier t ON CONVERT(m.platform_symbol USING utf8mb4) COLLATE utf8mb4_unicode_ci = t.symbol AND t.group_code='default' AND t.tier_no=1 AND t.enabled=1 WHERE m.max_leverage > t.max_leverage")
if [ "${MISMATCH:-1}" = "0" ]; then echo "PASS  mapping↔tier1 杠杆对齐（错配 0）"; else echo "FAIL  mapping↔tier1 错配 ${MISMATCH:-<查询失败>} 行"; FAIL=1; fi

echo "=== 3. trading seed（迁移产出，V38 基线） ==="
check_ge "t_symbol_leverage_tier"     "SELECT COUNT(*) FROM falconx_trading.t_symbol_leverage_tier" 6000
check_ge "t_fx_pause_behavior"        "SELECT COUNT(*) FROM falconx_trading.t_fx_pause_behavior" 8
check_ge "t_notification_template"    "SELECT COUNT(*) FROM falconx_trading.t_notification_template" 14
check_ge "t_risk_config"              "SELECT COUNT(*) FROM falconx_trading.t_risk_config" 5
check_ge "t_risk_market_config"       "SELECT COUNT(*) FROM falconx_trading.t_risk_market_config" 3
check_ge "t_symbol_correlation_group" "SELECT COUNT(*) FROM falconx_trading.t_symbol_correlation_group" 4
# 风控设计准则（V38）：tier 全量 lev×mm ≤ 0.5
VIOL=$(q "SELECT COUNT(*) FROM falconx_trading.t_symbol_leverage_tier WHERE max_leverage*mm_rate > 0.5 AND enabled=1")
if [ "${VIOL:-1}" = "0" ]; then echo "PASS  tier lev×mm≤0.5（违规 0）"; else echo "FAIL  tier lev×mm>0.5 共 ${VIOL:-<查询失败>} 行（V38 未生效?）"; FAIL=1; fi

echo "=== 4. console seed + 超管自举（console 首次启动后） ==="
check_ge "t_admin_menu"  "SELECT COUNT(*) FROM falconx_console.t_admin_menu" 32
check_ge "t_admin_role"  "SELECT COUNT(*) FROM falconx_console.t_admin_role" 1
SUPER=$(q "SELECT COUNT(*) FROM falconx_console.t_admin_user WHERE username='superadmin'")
if [ "${SUPER:-0}" -ge 1 ] 2>/dev/null; then
  echo "PASS  superadmin 已自举（初始密码见 ConsoleServiceProperties: falconx-admin-init，首登须改密）"
else
  echo "WARN  superadmin 尚未自举（console-service 启动一次后由 DefaultSuperAdminInitializer 创建）"
fi

echo "=== 5. ClickHouse（market 首次启动后） ==="
CHT=$(docker exec "$CH_CONTAINER" clickhouse-client --user "$CH_USER" --password "$CH_PASSWORD" -q \
  "SELECT count() FROM system.tables WHERE database='falconx_market_analytics' AND name IN ('kline','quote_tick')" 2>/dev/null)
if [ "${CHT:-0}" = "2" ]; then echo "PASS  ClickHouse kline/quote_tick 表存在"; else echo "FAIL  ClickHouse 表缺失（CHT=${CHT:-<查询失败>}；market 启动会自动跑 CH_V*.sql）"; FAIL=1; fi

echo
if [ "$FAIL" = "0" ]; then echo "✓ 冷启动基础数据自检全部通过"; else echo "✗ 存在 FAIL 项，见上"; fi
exit $FAIL
