#!/usr/bin/env bash
# FalconX 本地全栈启动脚本（WSL / macOS dev）
#
# 用法:    bash scripts/local-start.sh
# 停止:    bash scripts/local-stop.sh
#
# 启动模式：本机直接跑 jar + npm run dev（用 screen 后台保活），连本机 docker-compose.yml
# 起的 4 容器基础设施（mysql/redis/kafka/clickhouse）。**适合开发调试 + IDE debug attach**。
#
# 配套脚本:
#   - scripts/local-stop.sh        本地停止（停 screen + docker compose stop）
#   - scripts/server-start.sh      服务器侧（docker compose -f docker-compose.prod.yml up）
#   - scripts/server-stop.sh       服务器侧停止
#   - scripts/deploy-to-server.sh  本地一键部署到服务器（mvn + build + scp + ssh recreate）
#
# 前置: docker / java 25 / mvn / node / 已构建 jar / .env 已恢复 / tools/lp-truststore.p12 存在
#
# .env 排错（2026-05-25 复盘）：market/wallet 通过 `set -a && source .env` 加载环境变量。
# 助记词、API key 等含空格或特殊字符的值必须用引号包裹，例如：
#   FALCONX_WALLET_TRC20_MNEMONIC_WORDS="word1 word2 word3 ..."
# 若漏引号，bash 会把空格后的 token 当成命令并报 "command not found"，source 返回 127，
# 后续 `&& exec java ...` 整段被跳过，screen 启动后立即退出（端口不监听 + 日志无新内容）。

set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
LOG_DIR="$ROOT/logs/local"
mkdir -p "$LOG_DIR"

# JVM 堆防御（2026-05-20 引入）：所有 service 显式 -Xms/-Xmx + HeapDumpOnOutOfMemoryError +
# ExitOnOutOfMemoryError。原因：market-service 5 天 OOM 死锁的根因之一是 JVM 默认堆（≈物理内存 1/4）
# 没有上限保护，OOM 后又不会自杀进入死循环。每个 service 按业务量大致分配，总 max 6.5GB << 15GB 物理。
# 业务量分级：
#   - identity：JWT 签发，轻量 → 512m
#   - gateway / wallet / console：中量（路由 / 链扫描 / 管理读）→ 1g
#   - trading-core / market：重量（订单/持仓 / quote 累积）→ 2g
JVM_DUMP_OPTS="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$LOG_DIR/ -XX:+ExitOnOutOfMemoryError"
JVM_HEAP_LIGHT="-Xms128m -Xmx512m"
JVM_HEAP_MED="-Xms256m -Xmx1g"
JVM_HEAP_HEAVY="-Xms512m -Xmx2g"

echo "=== 1. 基础设施 docker compose ==="
docker compose up -d
echo "等待容器健康..."
for i in $(seq 1 30); do
  total=$(docker ps --filter "name=falconx-" --format '{{.Names}}' | wc -l)
  healthy=$(docker ps --filter "name=falconx-" --filter "health=healthy" --format '{{.Names}}' | wc -l)
  if [ "$total" -ge 4 ] && [ "$total" = "$healthy" ]; then
    echo "all $healthy containers healthy"
    break
  fi
  sleep 2
done
docker ps --filter "name=falconx-" --format "table {{.Names}}\t{{.Status}}"

start_screen() {
  local name=$1
  local cmd=$2
  screen -S "$name" -X quit 2>/dev/null || true
  sleep 1
  screen -dmS "$name" bash -lc "$cmd"
  echo "started: $name"
}

wait_port() {
  local port=$1
  local name=$2
  local max=$3
  for i in $(seq 1 "$max"); do
    if ss -tln 2>/dev/null | grep -q ":${port} "; then
      echo "✓ $name listening on $port (after ${i}*5s)"
      return 0
    fi
    sleep 5
  done
  echo "✗ $name did NOT come up on $port within $((max*5))s"
  return 1
}

echo ""
echo "=== 2. identity-service (18081) ==="
start_screen falconx-identity "cd $ROOT && exec java $JVM_HEAP_LIGHT $JVM_DUMP_OPTS -jar falconx-identity-service/target/falconx-identity-service-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev >> $LOG_DIR/identity-service.log 2>&1"

echo ""
echo "=== 3. gateway (18080) ==="
start_screen falconx-gateway "cd $ROOT && exec java $JVM_HEAP_MED $JVM_DUMP_OPTS -jar falconx-gateway/target/falconx-gateway-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev >> $LOG_DIR/gateway.log 2>&1"

wait_port 18081 identity 18
wait_port 18080 gateway 12

echo ""
echo "=== 4. trading-core-service (18083) — 必须先于 market ==="
start_screen falconx-trading "cd $ROOT && exec java $JVM_HEAP_HEAVY $JVM_DUMP_OPTS -jar falconx-trading-core-service/target/falconx-trading-core-service-1.0.0-SNAPSHOT.jar --falconx.trading.cache.quote-ttl=1d --falconx.trading.stale.max-age=1d >> $LOG_DIR/trading-core-service.log 2>&1"
wait_port 18083 trading-core 18

# trading-core 注册 Kafka consumer 后再启动 market
sleep 3

echo ""
echo "=== 5. market-service (18082, 加载 .env + truststore) ==="
start_screen falconx-market "cd $ROOT && set -a && source .env && set +a && exec java \
  $JVM_HEAP_HEAVY $JVM_DUMP_OPTS \
  -Djavax.net.ssl.trustStore=$ROOT/tools/lp-truststore.p12 \
  -Djavax.net.ssl.trustStoreType=PKCS12 \
  -Djavax.net.ssl.trustStorePassword=falconx-lp \
  -DFALCONX_MARKET_DB_USERNAME=root \
  -DFALCONX_MARKET_DB_PASSWORD=root \
  -jar falconx-market-service/target/falconx-market-service-1.0.0-SNAPSHOT.jar \
  --falconx.market.analytics.quote-flush-interval=10000 \
  >> $LOG_DIR/market-service.log 2>&1"

echo ""
echo "=== 6. wallet-service (18084, 加载 .env + truststore) ==="
start_screen falconx-wallet "cd $ROOT && set -a && source .env && set +a && exec env FALCONX_WALLET_DB_USERNAME=root FALCONX_WALLET_DB_PASSWORD=root FALCONX_WALLET_ETH_ACCOUNT_XPUB='xpub6DB3tvxWiLh7aM2mLjwUbRfNGw65C1SAz8ayQbhsXnHgBc1gywUyrwen7cb2XJ8hDWduFZGE2AMRzJBSC2p6ci62NRdBvbtzWGvGNjTRsT5' FALCONX_WALLET_TRON_ACCOUNT_XPUB='xpub6DL73xExtJREFmDHsmeqTiWA3hiSAw3JirYQcsbaN3tMdpRDW3rqXQymJChEJpfEgBZkDe7d5VQe7N1Y9PaxcicPobqaDAzy9habuhAzGXN' java \
  $JVM_HEAP_MED $JVM_DUMP_OPTS \
  -Djavax.net.ssl.trustStore=$ROOT/tools/wallet-truststore.p12 \
  -Djavax.net.ssl.trustStoreType=PKCS12 \
  -Djavax.net.ssl.trustStorePassword=changeit \
  -jar falconx-wallet-service/target/falconx-wallet-service-1.0.0-SNAPSHOT.jar \
  --falconx.wallet.chains.eth.scan-interval=5m \
  --falconx.wallet.chains.bsc.scan-interval=5m \
  --falconx.wallet.chains.tron.scan-interval=5m \
  --falconx.wallet.chains.sol.scan-interval=5m \
  >> $LOG_DIR/wallet-service.log 2>&1"

echo ""
echo "=== 7. console-service (18085) ==="
start_screen falconx-console "cd $ROOT && exec java $JVM_HEAP_MED $JVM_DUMP_OPTS -jar falconx-console-service/target/falconx-console-service-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev >> $LOG_DIR/console-service.log 2>&1"

wait_port 18082 market 24
wait_port 18084 wallet 18
wait_port 18085 console 18

echo ""
echo "=== 8. 客户端前端 (5200, --host 0.0.0.0) ==="
start_screen falconx-frontend "cd $ROOT/falconx-frontend && exec npm run dev -- --host 0.0.0.0 >> $LOG_DIR/falconx-frontend.log 2>&1"

echo ""
echo "=== 9. 管理端前端 (5300, --host 0.0.0.0) ==="
start_screen falconx-console-frontend "cd $ROOT/falconx-console-frontend && exec npm run dev -- --host 0.0.0.0 >> $LOG_DIR/falconx-console-frontend.log 2>&1"

wait_port 5200 client-frontend 12
wait_port 5300 admin-frontend 12

echo ""
echo "=== 10. 最终状态 ==="
echo "[端口]"
ss -tln 2>/dev/null | grep -E ":(18080|18081|18082|18083|18084|18085|5200|5300) " | awk '{print "  "$4}' | sort
echo ""
echo "[screen]"
screen -ls 2>/dev/null | grep falconx | awk '{print "  "$1}'

echo ""
echo "✓ 全部启动完成。日志: $LOG_DIR/"
echo ""
echo "查看实时日志: tail -f $LOG_DIR/<service>.log"
echo "停止某服务:   screen -S falconx-<service> -X quit"
echo "停止所有:     screen -ls | grep falconx | awk -F. '{print \$1}' | awk '{print \$1}' | xargs -r -I{} screen -S {} -X quit"
