#!/usr/bin/env bash
# FalconX 本地一键部署到服务器
#
# 用法:
#   bash scripts/deploy-to-server.sh                              # 全量：mvn + 7 镜像 + 部署
#   bash scripts/deploy-to-server.sh -s market-service            # 只 market
#   bash scripts/deploy-to-server.sh -s admin-frontend            # 只管理端前端
#   bash scripts/deploy-to-server.sh -s market-service,gateway    # 多服务
#   bash scripts/deploy-to-server.sh --skip-mvn                   # 跳 mvn（jar 已就绪）
#   bash scripts/deploy-to-server.sh --skip-build                 # 镜像已 build，只 scp + recreate
#   bash scripts/deploy-to-server.sh -h                           # 帮助
#
# 流程: mvn package → docker build → docker save → scp → ssh load → recreate → 重启 edge → 烟雾测试
#
# 前置：
#   - 本机能 ssh ubuntu@$SERVER_HOST 无密码登录
#   - 本机已构建 tools/{lp,wallet}-truststore.p12
#   - 服务器上 /home/ubuntu/falconx/ 目录存在且有 docker-compose.prod.yml

set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)

# ============================== 配置 ==============================
SERVER_HOST="${SERVER_HOST:-ubuntu@10.143.170.189}"
SERVER_DIR="${SERVER_DIR:-/home/ubuntu/falconx}"
COMPOSE_FILE="docker-compose.prod.yml"
TAR_PATH="/tmp/falconx-update.tar"
TARGET_PLATFORM="${TARGET_PLATFORM:-linux/amd64}"

ALL_BACKEND="identity-service gateway market-service trading-core-service wallet-service console-service"
ALL_FRONTEND="client-frontend admin-frontend"
ALL_EDGE="edge"

# ============================== 解析参数 ==============================
SERVICES=""
SKIP_MVN=0
SKIP_BUILD=0
while [ $# -gt 0 ]; do
  case "$1" in
    -s|--services)  SERVICES="$2"; shift 2 ;;
    --skip-mvn)     SKIP_MVN=1; shift ;;
    --skip-build)   SKIP_BUILD=1; SKIP_MVN=1; shift ;;
    -h|--help)
      grep "^#" "$0" | head -30
      exit 0
      ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

# 默认部署全部业务服务（不含基础设施 mysql/redis/kafka/clickhouse）
if [ -z "$SERVICES" ]; then
  SERVICES="$ALL_BACKEND $ALL_FRONTEND"
else
  # 把逗号分隔变成空格
  SERVICES=$(echo "$SERVICES" | tr ',' ' ')
fi

# 哪些是后端（需要 mvn jar）
BACKEND_SVCS=""
FRONTEND_SVCS=""
for svc in $SERVICES; do
  case "$svc" in
    identity-service|gateway|market-service|trading-core-service|wallet-service|console-service)
      BACKEND_SVCS="$BACKEND_SVCS $svc" ;;
    client-frontend|admin-frontend|edge)
      FRONTEND_SVCS="$FRONTEND_SVCS $svc" ;;
    *)
      echo "✗ 未识别 service: $svc"; exit 1 ;;
  esac
done
BACKEND_COUNT=$(echo "$BACKEND_SVCS" | wc -w | tr -d ' ')

# ============================== 前置检查 ==============================
echo "=== 0. 前置检查 ==="
echo "  服务器: $SERVER_HOST:$SERVER_DIR"
echo "  部署服务: $SERVICES"
echo "  跳 mvn:   $([ $SKIP_MVN = 1 ] && echo 是 || echo 否)"
echo "  跳 build: $([ $SKIP_BUILD = 1 ] && echo 是 || echo 否)"
echo "  目标平台: $TARGET_PLATFORM"
echo

if ! ssh -o ConnectTimeout=5 -o BatchMode=yes "$SERVER_HOST" 'echo ssh-ok' >/dev/null 2>&1; then
  echo "✗ ssh $SERVER_HOST 不通"; exit 1
fi
if [ -n "$BACKEND_SVCS" ] && { [ ! -f "$ROOT/tools/lp-truststore.p12" ] || [ ! -f "$ROOT/tools/wallet-truststore.p12" ]; }; then
  echo "✗ tools/{lp,wallet}-truststore.p12 缺失"; exit 1
fi

# ============================== 1. mvn package ==============================
if [ "$SKIP_MVN" = 0 ] && [ -n "$BACKEND_SVCS" ]; then
  echo "=== 1. mvn package (test 跳过) ==="
  START=$(date +%s)
  mvn -q clean package -Dmaven.test.skip=true 2>&1 | tail -5
  MVN_RC=${PIPESTATUS[0]}   # 取 mvn 真实退出码，而非管道末端 tail（恒 0）
  echo "mvn 耗时: $(($(date +%s) - START)) 秒"
  if [ "$MVN_RC" -ne 0 ]; then
    echo "✗ mvn package 失败 (rc=$MVN_RC)，中止部署（避免后续发布旧 jar）"
    exit 1
  fi
  echo
else
  echo "=== 1. 跳过 mvn package ==="
fi

# ============================== 2. docker build ==============================
if [ "$SKIP_BUILD" = 0 ]; then
  echo "=== 2. docker compose build ==="
  START=$(date +%s)
  DOCKER_DEFAULT_PLATFORM="$TARGET_PLATFORM" docker compose -f "$COMPOSE_FILE" build $SERVICES 2>&1 | tail -8
  BUILD_RC=${PIPESTATUS[0]}   # 取 docker build 真实退出码，而非管道末端 tail（恒 0）
  echo "build 耗时: $(($(date +%s) - START)) 秒"
  if [ "$BUILD_RC" -ne 0 ]; then
    echo "✗ docker build 失败 (rc=$BUILD_RC)，中止部署（避免 save/scp/recreate 旧镜像导致‘假部署’）"
    exit 1
  fi
  echo
else
  echo "=== 2. 跳过 docker build ==="
fi

# ============================== 3. save tar ==============================
echo "=== 3. docker save → tar ==="
IMAGES=""
for svc in $SERVICES; do
  case "$svc" in
    client-frontend)  IMAGES="$IMAGES falconx-frontend:demo" ;;
    admin-frontend)   IMAGES="$IMAGES falconx-console-frontend:demo" ;;
    edge)             IMAGES="$IMAGES falconx-edge:demo" ;;
    *)                IMAGES="$IMAGES falconx-$svc:demo" ;;
  esac
done
docker save $IMAGES -o "$TAR_PATH"
echo "tar 大小: $(du -h "$TAR_PATH" | cut -f1)"
echo

# ============================== 4. scp + rsync 配置 ==============================
echo "=== 4. scp tar + rsync 配置 ==="
START=$(date +%s)
scp "$TAR_PATH" "$SERVER_HOST:$SERVER_DIR/" &
SCP_PID=$!
rsync -av --quiet "$COMPOSE_FILE" "$SERVER_HOST:$SERVER_DIR/" 2>&1 | tail -3 || true
if [ -d "$ROOT/deploy/docker" ]; then
  rsync -av --quiet "$ROOT/deploy/docker/" "$SERVER_HOST:$SERVER_DIR/deploy/docker/" 2>&1 | tail -3 || true
fi
wait $SCP_PID
echo "scp 耗时: $(($(date +%s) - START)) 秒"
echo

# ============================== 5. ssh load + recreate ==============================
echo "=== 5. ssh load + recreate ==="
ssh "$SERVER_HOST" "
set -uo pipefail
cd $SERVER_DIR

echo '  load 镜像...'
docker load -i falconx-update.tar 2>&1 | tail -10

echo
echo '  recreate 容器: $SERVICES'
docker compose -f $COMPOSE_FILE up -d --no-build --force-recreate $SERVICES 2>&1 | tail -10

echo
if [ $BACKEND_COUNT -gt 0 ]; then
  echo '  等后端 Started（最多 60s）...'
  for i in \$(seq 1 20); do
    ready=\$(docker compose -f $COMPOSE_FILE logs --no-color --since 90s $BACKEND_SVCS 2>&1 | grep -c 'Started.*Application')
    if [ \"\$ready\" -ge $BACKEND_COUNT ]; then break; fi
    sleep 3
  done
else
  echo '  无后端服务，跳过后端 Started 等待'
fi

echo
echo '  重启 edge 刷 DNS...'
docker compose -f $COMPOSE_FILE restart edge 2>&1 | tail -2
sleep 3
"

# ============================== 6. 烟雾测试 ==============================
echo
echo "=== 6. 公网烟雾 ==="
curl -sI -o /dev/null -w "  https://app-falconx/                  HTTP=%{http_code}\n" --max-time 10 https://app-falconx.lifebyteapp.dev/
curl -sX POST -H "Content-Type: application/json" -d "{}" \
  https://app-falconx.lifebyteapp.dev/api/v1/auth/login \
  -w "  https://app-falconx/api/v1/auth/login HTTP=%{http_code}\n" -o /dev/null --max-time 10
curl -sI -o /dev/null -w "  https://admin-falconx/                HTTP=%{http_code}\n" --max-time 10 https://admin-falconx.lifebyteapp.dev/
curl -sX POST -H "Content-Type: application/json" -d "{}" \
  https://admin-falconx.lifebyteapp.dev/admin/auth/login \
  -w "  https://admin-falconx/admin/auth/login HTTP=%{http_code}\n" -o /dev/null --max-time 10

echo
echo "✓ 部署完成"
echo "  入口: https://app-falconx.lifebyteapp.dev/ | https://admin-falconx.lifebyteapp.dev/"
