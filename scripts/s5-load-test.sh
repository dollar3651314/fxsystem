#!/usr/bin/env bash
# Sprint 3 S5 sanity：连续 5 分钟采集 market dispatcher partition queue 分布
# 用法：bash scripts/s5-load-test.sh
# 期望：dispatcher 分布均匀、消费跟得上、无积压
set -euo pipefail

HOST="${MARKET_HOST:-localhost}"
PORT="${MARKET_PORT:-18082}"
BASE="http://${HOST}:${PORT}"

echo "=== 1. 前置：market-service 必须在 ${BASE} 监听 ==="
if ! curl -s --max-time 3 -f "${BASE}/actuator/health" > /dev/null; then
    echo "✗ market-service 未在 ${BASE} 监听，请先启动"
    exit 1
fi

echo
echo "=== 2. 连续 5 分钟采集 dispatcher queue size ==="
OUT_CSV="/tmp/s5-dispatcher-queue.csv"
echo "time,partition,queue_size" > "${OUT_CSV}"

# 读取 partition 数（首次拉一次 metrics 取 availableTags）
PARTITIONS=$(curl -s --max-time 5 "${BASE}/actuator/metrics/market.ingestion.dispatcher.queue.size" 2>/dev/null \
    | jq -r '.availableTags[]? | select(.tag=="partition") | .values[]' 2>/dev/null \
    | sort -n)

if [ -z "${PARTITIONS}" ]; then
    echo "✗ 未找到 market.ingestion.dispatcher.queue.size metric — 是否未启用 async-downstream？"
    exit 1
fi

echo "[发现 partition: $(echo ${PARTITIONS} | tr '\n' ' ')]"
echo

for i in $(seq 1 60); do
    timestamp=$(date '+%H:%M:%S')
    for partition in ${PARTITIONS}; do
        queue_size=$(curl -s --max-time 3 "${BASE}/actuator/metrics/market.ingestion.dispatcher.queue.size?tag=partition:${partition}" 2>/dev/null \
            | jq -r '.measurements[0].value' 2>/dev/null || echo 0)
        echo "${timestamp},${partition},${queue_size}" >> "${OUT_CSV}"
    done
    sleep 5
done

echo
echo "=== 3. 结果统计 ==="
echo "[Partition queue 高峰值]"
awk -F',' 'NR>1 {if ($3 > max[$2]) max[$2] = $3} END {for (p in max) print "  partition", p, "max queue:", max[p]}' "${OUT_CSV}" | sort -n -k2

echo
echo "[Partition queue 平均值]"
awk -F',' 'NR>1 {sum[$2] += $3; count[$2]++} END {for (p in sum) printf "  partition %s avg queue: %.2f\n", p, sum[p]/count[p]}' "${OUT_CSV}" | sort -n -k2

echo
echo "=== 4. 期望 ==="
echo "  - 多 partition 各自有数据（hash 分布合理）"
echo "  - 平均 queue size < 100（消费速度跟得上）"
echo "  - 高峰 queue size < 5000（< 5000 不告警）"
echo "  - 任何 partition queue 持续 > 5000 → 调高 partition_count 或排查下游瓶颈"
echo
echo "原始数据：${OUT_CSV}"
