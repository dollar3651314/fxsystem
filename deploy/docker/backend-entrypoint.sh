#!/bin/sh
# FalconX 后端通用 entrypoint
# 自动按容器 hostname 隔离 heap dump / GC log 目录，方便 OOM 后排查
# host volume 挂在 /var/log/falconx，宿主机 ./jvm-logs/<service>/{heap,gc} 持久化

set -e

HOST=$(hostname)
HEAP_DIR="/var/log/falconx/heap/${HOST}"
GC_DIR="/var/log/falconx/gc/${HOST}"
mkdir -p "${HEAP_DIR}" "${GC_DIR}"

# JAVA_OPTS 由 compose 注入（heap / 业务参数）
# 这里追加 OOM dump + GC log 标配（所有后端通用，不影响业务参数）
# OnOutOfMemoryError=kill -9 %p 强杀 JVM；compose restart: unless-stopped 会拉起新容器
exec java ${JAVA_OPTS} \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath="${HEAP_DIR}/" \
  -XX:OnOutOfMemoryError="kill -9 %p" \
  -Xlog:gc*:file="${GC_DIR}/gc.log":time,uptime,level,tags:filecount=5,filesize=20M \
  -XX:NativeMemoryTracking=summary \
  -jar /app/app.jar
