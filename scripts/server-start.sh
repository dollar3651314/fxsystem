#!/usr/bin/env bash
# FalconX 服务器启动脚本（在 EC2 上跑）
#
# 用法 (本机):    ssh ubuntu@10.143.170.189 'bash /home/ubuntu/falconx/scripts/server-start.sh'
# 用法 (服务器): cd /home/ubuntu/falconx && bash scripts/server-start.sh
#
# 启动模式：docker compose -f docker-compose.prod.yml up -d
#   - 13 容器：mysql/redis/kafka/clickhouse + 6 后端 + 2 前端 + edge
#   - 镜像必须已 load（用 scripts/deploy-to-server.sh 推过来）
#
# 配套脚本: scripts/server-stop.sh / scripts/deploy-to-server.sh

set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
COMPOSE="docker compose -f docker-compose.prod.yml"

echo "=== 1. 检查前置 ==="
if [ ! -f "$ROOT/docker-compose.prod.yml" ]; then
  echo "✗ 未找到 docker-compose.prod.yml，当前目录 $ROOT 不是 falconx 部署目录"; exit 1
fi
if ! command -v docker >/dev/null; then
  echo "✗ docker 未安装"; exit 1
fi

echo "=== 2. docker compose up -d --no-build ==="
$COMPOSE up -d --no-build 2>&1 | tail -15

echo
echo "=== 3. 等基础设施 healthy（最多 90s）==="
for i in $(seq 1 30); do
  healthy=$(docker ps --filter "name=falconx-" --filter "health=healthy" --format '{{.Names}}' | wc -l)
  echo "  $((i*3))s: $healthy/4 healthy"
  if [ "$healthy" -ge 4 ]; then break; fi
  sleep 3
done

echo
echo "=== 4. 等 6 个后端 Spring Started ==="
for i in $(seq 1 30); do
  started=$(${COMPOSE} logs --no-color --since 3m 2>&1 | grep -c "Started.*Application")
  echo "  $((i*3))s: $started/6 Started"
  if [ "$started" -ge 6 ]; then break; fi
  sleep 3
done

echo
echo "=== 5. 重启 edge 刷 DNS（避免后端 IP 变化导致 502）==="
$COMPOSE restart edge 2>&1 | tail -2
sleep 3

echo
echo "=== 6. 容器最终状态 ==="
$COMPOSE ps --format "table {{.Service}}\t{{.State}}\t{{.Status}}"

echo
echo "=== 7. 烟雾测试（local edge :80）==="
curl -sI -o /dev/null -w "  / (default)              HTTP=%{http_code}\n" http://localhost/
curl -sI -H "Host: app-falconx.lifebyteapp.dev" -o /dev/null -w "  app/                     HTTP=%{http_code}\n" http://localhost/
curl -sX POST -H "Host: app-falconx.lifebyteapp.dev" -H "Content-Type: application/json" -d "{}" \
  http://localhost/api/v1/auth/login -o /dev/null -w "  app/api/v1/auth/login    HTTP=%{http_code} (期望 400)\n"
curl -sI -H "Host: admin-falconx.lifebyteapp.dev" -o /dev/null -w "  admin/                   HTTP=%{http_code}\n" http://localhost/
curl -sX POST -H "Host: admin-falconx.lifebyteapp.dev" -H "Content-Type: application/json" -d "{}" \
  http://localhost/admin/auth/login -o /dev/null -w "  admin/admin/auth/login   HTTP=%{http_code} (期望 400)\n"

echo
echo "✓ 服务器启动完成"
echo "  入口: https://app-falconx.lifebyteapp.dev/ | https://admin-falconx.lifebyteapp.dev/"
echo "  停止: bash scripts/server-stop.sh"
echo "  日志: $COMPOSE logs -f <service>"
