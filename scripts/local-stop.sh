#!/usr/bin/env bash
# FalconX 本地停止脚本（配 local-start.sh）
#
# 用法:  bash scripts/local-stop.sh         停应用服务（screen）但保留基础设施容器 + 数据
#        bash scripts/local-stop.sh --all   连基础设施容器一起 stop（数据卷保留）
#        bash scripts/local-stop.sh --purge 全停 + docker compose down -v 删数据卷（危险，要再确认）
#
# 不会删 docker 镜像。

set -uo pipefail
cd "$(dirname "$0")/.."

MODE="${1:-services}"
case "$MODE" in
  --all)    MODE=all ;;
  --purge)  MODE=purge ;;
  ""|--services) MODE=services ;;
  *)        echo "未知参数: $MODE"; exit 1 ;;
esac

echo "=== 1. 停 8 个 screen 应用服务 ==="
SCREENS=$(screen -ls 2>/dev/null | grep falconx | awk -F. '{print $1}' | awk '{print $1}' || true)
if [ -z "$SCREENS" ]; then
  echo "  无运行中的 falconx-* screen"
else
  echo "$SCREENS" | xargs -r -I{} screen -S {} -X quit
  sleep 1
  echo "  已停: $(echo "$SCREENS" | wc -l) 个"
fi

case "$MODE" in
  services)
    echo
    echo "✓ 应用服务已停，基础设施容器保留运行（mysql/redis/kafka/clickhouse）"
    echo "  完全停止: bash scripts/local-stop.sh --all"
    ;;
  all)
    echo
    echo "=== 2. 停基础设施容器（数据卷保留）==="
    docker compose stop 2>&1 | tail -10
    echo "✓ 全部停止，数据保留"
    echo "  再起: bash scripts/local-start.sh"
    ;;
  purge)
    echo
    echo "⚠️  PURGE 模式将删除所有本地数据卷（mysql/redis/clickhouse 数据全没）"
    read -p "确认输入 'yes' 继续: " ans
    if [ "$ans" = "yes" ]; then
      docker compose down -v
      echo "✓ 全部清空"
    else
      echo "已取消"
    fi
    ;;
esac
