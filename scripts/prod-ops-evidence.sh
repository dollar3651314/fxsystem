#!/usr/bin/env bash
# =============================================================================
# FalconX 部署/运行证据采集脚本（PROD-OPS-EVIDENCE）
#
# 用途：按 docs/operations/生产观测与回滚手册.md §4 模板，对当前运行的 FalconX 栈
#       自动采集部署/运行证据并生成 Markdown，供准生产/生产部署前后留痕，或本地演练归档。
#
# 用法：
#   scripts/prod-ops-evidence.sh [输出文件] [服务清单逗号分隔]
#   例：scripts/prod-ops-evidence.sh docs/test/archive/PROD-OPS-EVIDENCE-LOCAL-2026-06-02.md \
#         identity,gateway,trading-core,console
#
# 仅做只读采集（curl /actuator + 读日志 + git 状态），不改动任何服务或数据。
# canary login/recon 需注入 CANARY_USER_PASSWORD / CANARY_ADMIN_PASSWORD；未注入则仅跑 health-all。
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

OUT="${1:-docs/test/archive/PROD-OPS-EVIDENCE-$(date +%F).md}"
SCOPE="${2:-gateway,identity,market,trading-core,wallet,console}"
CANARY_JAR="falconx-canary/target/falconx-canary-1.0.0-SNAPSHOT.jar"
LOG_DIR="${FALCONX_LOG_DIR:-$ROOT/logs/local}"

# service -> port
declare -A PORT=(
  [gateway]=18080 [identity]=18081 [market]=18082
  [trading-core]=18083 [wallet]=18084 [console]=18085
)

ts() { date "+%Y-%m-%d %H:%M:%S%z"; }
section() { printf '\n## %s\n\n' "$1" >>"$OUT"; }

mkdir -p "$(dirname "$OUT")"
: >"$OUT"

{
  echo "# PROD-OPS-EVIDENCE 部署/运行证据"
  echo
  echo "> 采集时间：$(ts)"
  echo "> 采集主机：$(hostname) / $(uname -srm)"
  echo "> 采集范围：$SCOPE"
  echo "> 脚本：scripts/prod-ops-evidence.sh（只读采集，按手册 §4 模板）"
} >>"$OUT"

# --- 1. Git / 构建状态 ---
section "1. Git 与构建状态"
{
  echo '```'
  echo "Git commit： $(git rev-parse HEAD)"
  echo "Git 分支：   $(git rev-parse --abbrev-ref HEAD)"
  echo "工作区状态（--short）："
  git status --short || true
  echo
  echo "已构建后端 jar："
  ls -1 falconx-*/target/*-SNAPSHOT.jar 2>/dev/null | sed 's#^#  #' || echo "  （无）"
  echo '```'
} >>"$OUT"

# --- 2. 服务健康检查 ---
section "2. 服务健康检查（/actuator/health）"
{
  echo "| 服务 | 端口 | HTTP | status |"
  echo "| --- | --- | --- | --- |"
  IFS=',' read -ra SVCS <<<"$SCOPE"
  for s in "${SVCS[@]}"; do
    p="${PORT[$s]:-?}"
    body="$(curl -fsS -m 5 "http://localhost:$p/actuator/health" 2>/dev/null)"
    rc=$?
    if [ $rc -eq 0 ]; then
      st="$(printf '%s' "$body" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("status","?"))' 2>/dev/null || echo "?")"
      echo "| $s | $p | 200 | $st |"
    else
      echo "| $s | $p | 不可达 | DOWN |"
    fi
  done
} >>"$OUT"

# --- 3. Canary ---
section "3. Canary 关键链路"
{
  if [ -f "$CANARY_JAR" ]; then
    echo '```'
    echo "\$ java -jar $CANARY_JAR health-all"
    java -jar "$CANARY_JAR" health-all 2>&1; echo "exitCode=$?"
    if [ -n "${CANARY_USER_PASSWORD:-}" ]; then
      echo; echo "\$ java -jar canary login"; java -jar "$CANARY_JAR" login 2>&1; echo "exitCode=$?"
    else
      echo; echo "(login 跳过：未注入 CANARY_USER_PASSWORD)"
    fi
    if [ -n "${CANARY_ADMIN_PASSWORD:-}" ]; then
      echo; echo "\$ java -jar canary recon"; java -jar "$CANARY_JAR" recon 2>&1; echo "exitCode=$?"
    else
      echo; echo "(recon 跳过：未注入 CANARY_ADMIN_PASSWORD)"
    fi
    echo '```'
  else
    echo "canary jar 未构建（$CANARY_JAR）；跳过。"
  fi
} >>"$OUT"

# --- 4. 热路径指标快照 ---
section "4. 热路径指标快照（/actuator/prometheus）"
{
  echo '```'
  for s in market trading-core; do
    p="${PORT[$s]}"
    echo "# $s ($p) falconx_ 指标条目数："
    n="$(curl -fsS -m 5 "http://localhost:$p/actuator/prometheus" 2>/dev/null | grep -c '^falconx_' || echo 0)"
    echo "  falconx_* series = $n"
    curl -fsS -m 5 "http://localhost:$p/actuator/prometheus" 2>/dev/null \
      | grep -E '^falconx_(market_quote_pending_size|market_quote_dropped_total|trading_tick_worker_queue_total) ' | sed 's/^/  /' || true
  done
  echo '```'
} >>"$OUT"

# --- 5. 日志错误扫描 ---
section "5. 日志 ERROR/WARN 扫描"
{
  echo '```'
  if [ -d "$LOG_DIR" ]; then
    echo "（计数为日志文件累计值，logs/local/*.log 跨多次运行追加，非单次部署窗口）"
    for f in "$LOG_DIR"/*.log; do
      [ -f "$f" ] || continue
      e=$(grep -cE "\bERROR\b" "$f" 2>/dev/null || true); e=${e:-0}
      w=$(grep -cE "\bWARN\b"  "$f" 2>/dev/null || true); w=${w:-0}
      echo "$(basename "$f"): ERROR=$e WARN=$w"
    done
  else
    echo "日志目录不存在：$LOG_DIR"
  fi
  echo '```'
} >>"$OUT"

# --- 6. Kafka consumer lag ---
section "6. Kafka consumer lag"
{
  echo '```'
  docker exec falconx-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 --describe --all-groups 2>/dev/null \
    | awk 'NR==1 || $5!="-"{print}' | head -30 || echo "（kafka-consumer-groups 不可用）"
  echo '```'
} >>"$OUT"

echo
echo "证据已写入：$OUT"
