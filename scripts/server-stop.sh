#!/usr/bin/env bash
# FalconX 服务器停止脚本
#
# 用法 (本机):    ssh ubuntu@10.143.170.189 'bash /home/ubuntu/falconx/scripts/server-stop.sh'
# 用法 (服务器): cd /home/ubuntu/falconx && bash scripts/server-stop.sh
#
# 默认 stop：保留容器、数据卷、镜像（再起几秒就好）
# --down  : down 删容器（保留数据卷 + 镜像）
# --purge : down -v 删容器 + 数据卷（**会丢全部数据**，仅清空环境时用）

set -uo pipefail
cd "$(dirname "$0")/.."
COMPOSE="docker compose -f docker-compose.prod.yml"

MODE="${1:-stop}"
case "$MODE" in
  --stop|stop|"")  MODE=stop ;;
  --down)          MODE=down ;;
  --purge)         MODE=purge ;;
  *)               echo "未知参数: $MODE"; exit 1 ;;
esac

case "$MODE" in
  stop)
    echo "=== docker compose stop（保留容器 + 数据 + 镜像）==="
    $COMPOSE stop 2>&1 | tail -15
    echo
    echo "✓ 已停止。再起: bash scripts/server-start.sh"
    ;;
  down)
    echo "=== docker compose down（删容器，保留数据卷 + 镜像）==="
    $COMPOSE down 2>&1 | tail -15
    echo
    echo "✓ 容器已删，数据卷保留。再起: bash scripts/server-start.sh"
    ;;
  purge)
    echo "⚠️  PURGE 将删除所有数据卷（mysql/redis/clickhouse 数据全没）"
    read -p "确认输入 'yes' 继续: " ans
    if [ "$ans" = "yes" ]; then
      $COMPOSE down -v 2>&1 | tail -15
      echo "✓ 全部清空"
    else
      echo "已取消"
    fi
    ;;
esac
