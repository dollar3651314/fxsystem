# FalconX 启动 / 部署脚本

按"在哪跑、干什么"分两组：**本地**和**服务器**。所有脚本都支持 `bash <脚本>` 一键运行。

## 矩阵

| 脚本 | 在哪跑 | 用途 | 一键命令 |
|---|---|---|---|
| `local-start.sh` | 本地 WSL/macOS | 起 4 容器基础设施 + 6 jar (screen) + 2 vite dev | `bash scripts/local-start.sh` |
| `local-stop.sh` | 本地 | 停应用 / 全停 / 清数据 | `bash scripts/local-stop.sh [--all\|--purge]` |
| `server-start.sh` | EC2 上 | docker compose up + 等 healthy + restart edge + 烟雾 | `bash scripts/server-start.sh` |
| `server-stop.sh` | EC2 上 | stop / down / 清数据 | `bash scripts/server-stop.sh [--down\|--purge]` |
| `deploy-to-server.sh` | 本地 | mvn + build + scp + ssh recreate + 烟雾 一键到位 | `bash scripts/deploy-to-server.sh` |

---

## 本地开发 — 完整流程

**首次启动**：

```bash
# 前置：JDK 25 / mvn / node / docker / .env / tools/lp-truststore.p12 都已就位
mvn clean package -Dmaven.test.skip=true     # 打 jar
bash scripts/local-start.sh                  # 一键起 13 个东西
```

入口：
- 客户端 `http://localhost:5200`
- 管理端 `http://localhost:5300`
- gateway REST `http://localhost:18080`

**日常停 / 起**：

```bash
bash scripts/local-stop.sh         # 停 6 个 jar + 2 个 vite，保留 docker 基础设施
bash scripts/local-start.sh        # 再起（基础设施容器复用，jar 重启即可）

bash scripts/local-stop.sh --all   # 连基础设施也停（数据保留）
bash scripts/local-stop.sh --purge # 删数据卷（**谨慎**，要二次确认）
```

**改了 jar 想热重启某服务**：

```bash
mvn -pl falconx-<service> -am package -Dmaven.test.skip=true
screen -S falconx-<service> -X quit
bash scripts/local-start.sh         # 已 running 的服务会跳过，只起没起来的
```

---

## 服务器部署 — 完整流程

**首次部署**（在本地 WSL 跑）：

```bash
bash scripts/deploy-to-server.sh    # 默认全量：mvn + 8 个镜像 + scp + recreate
```

5-10 分钟完成。中途自动：
1. `mvn clean package -Dmaven.test.skip=true`
2. `docker compose -f docker-compose.prod.yml build`
3. `docker save -o /tmp/falconx-update.tar`
4. `scp` 到服务器（约 600 MB / 1-2 分钟）
5. `ssh` 服务器 `docker load + up -d --force-recreate + restart edge`
6. 公网烟雾测试

**增量部署单个服务**（最常用）：

```bash
bash scripts/deploy-to-server.sh -s market-service                # 只 market
bash scripts/deploy-to-server.sh -s admin-frontend                # 只管理端
bash scripts/deploy-to-server.sh -s market-service,gateway        # 多服务一起
```

**跳过部分阶段**：

```bash
bash scripts/deploy-to-server.sh --skip-mvn          # jar 已打好（前端改动不用 mvn）
bash scripts/deploy-to-server.sh --skip-build        # 镜像已 build（只想 scp + recreate）
```

**配置不同服务器**：

```bash
SERVER_HOST=ubuntu@<other-ip> bash scripts/deploy-to-server.sh
SERVER_DIR=/opt/falconx       bash scripts/deploy-to-server.sh
```

**只重启服务器（不更新代码）**：

```bash
ssh ubuntu@10.143.170.189 'bash /home/ubuntu/falconx/scripts/server-start.sh'
```

**停服务器**：

```bash
ssh ubuntu@10.143.170.189 'bash /home/ubuntu/falconx/scripts/server-stop.sh'         # 默认 stop（最快再起）
ssh ubuntu@10.143.170.189 'bash /home/ubuntu/falconx/scripts/server-stop.sh --down'  # 删容器保数据
```

---

## 典型场景对照

| 场景 | 命令 |
|---|---|
| "我本地写后端 + 本地测" | `mvn package → bash scripts/local-start.sh` |
| "本地跑通了想推到服务器" | `bash scripts/deploy-to-server.sh` |
| "只改了前端 1 个组件" | `bash scripts/deploy-to-server.sh -s admin-frontend --skip-mvn` |
| "改了 market 业务逻辑" | `bash scripts/deploy-to-server.sh -s market-service` |
| "想看服务器现状不更新" | `ssh ... 'docker compose -f docker-compose.prod.yml ps'` |
| "服务器故障要重启整个 stack" | `ssh ... 'bash scripts/server-start.sh'` |
| "服务器要停一下" | `ssh ... 'bash scripts/server-stop.sh'` |
| "本地不用了想清空" | `bash scripts/local-stop.sh --purge` |

---

## 前置条件清单

### 本地（local-start / local-stop / deploy-to-server）

- JDK 25（Eclipse Temurin / Adoptium）
- Maven 3.9+
- Node 24+
- Docker + docker compose 插件
- screen
- `.env` 配置文件（LP token / Alchemy RPC 等）
- `tools/lp-truststore.p12` / `tools/wallet-truststore.p12`
- 能 SSH 服务器（仅 deploy 需要）

### 服务器（server-start / server-stop）

- Ubuntu 22.04+
- Docker 25+ + compose plugin
- `/home/ubuntu/falconx/docker-compose.prod.yml`
- 已 load 业务镜像（用 deploy-to-server.sh 推过来）

---

## 注意事项

1. **edge nginx DNS 缓存问题**：后端容器 recreate 后内部 IP 变，edge 仍连旧 IP → 502。`server-start.sh` 和 `deploy-to-server.sh` 都会**自动 restart edge** 解决。
2. **ClickHouse 首次启动**：CLICKHOUSE_DB env 在挂 volume 后会被跳过，业务库 `falconx_market_analytics` 需要手动建（部署手册第 5 节）。后续 server-start 不会重复触发该问题。
3. **本地 8 个 screen 端口**：18080-18085 / 5200 / 5300。其他进程占了会导致 wait_port 超时。
4. **本地连远端基础设施**（替代 local-start）：用 SSH tunnel，详见 memory `aws-demo-deployment-state.md` §"后续开发约定"。

---

## 故障排查快查

```bash
# 服务器看实时日志
ssh ubuntu@10.143.170.189 'docker compose -f /home/ubuntu/falconx/docker-compose.prod.yml logs -f <service>'

# 本地看实时日志
tail -f logs/local/<service>.log

# 服务器 OOM dump 位置
ssh ubuntu@10.143.170.189 'ls /home/ubuntu/falconx/jvm-logs/heap/'

# 本地 OOM dump 位置
ls logs/local/*.hprof

# 强制全部重 build（缓存失效时）
bash scripts/deploy-to-server.sh --skip-mvn   # 然后 docker compose build --no-cache 手动
```
