# FalconX AWS 演示档部署手册

> 适用范围：单机演示档。生产部署不适用本手册。
>
> **2026-06-02 起标准流程见 §0（`scripts/deploy-to-server.sh` 一键部署）；§2-§5 的 worktree 手动流程仅作脚本失效时的 fallback 保留。**

## 0. 当前标准流程与运维守则（2026-06-02 起，真源）

### 0.1 一键部署（取代 §2-§5 手动流程）

```bash
bash scripts/deploy-to-server.sh                              # 全量：mvn + 8 镜像 + 部署 + 烟雾
bash scripts/deploy-to-server.sh -s trading-core-service      # 单服务
bash scripts/deploy-to-server.sh -s market-service,admin-frontend   # 多服务（逗号分隔）
```

脚本流程：mvn package（跳 test）→ docker build（linux/amd64）→ save tar → scp → 服务器 load + `up -d --force-recreate` → **restart edge** → 公网烟雾 4 项。服务名：`identity-service|gateway|market-service|trading-core-service|wallet-service|console-service|client-frontend|admin-frontend|edge`。约定不变：**数据/secret 不同步**（服务器 .env 持真实 secret，DB 数据绝不互相覆盖）；schema 由 Flyway 随服务启动自动 migrate，部署后核对 `flyway_schema_history` 两端版本一致。

### 0.2 手动运维守则（2026-06-03 事故教训，必须遵守）

1. **「容器假活」识别（2026-06-03 两次实锤，根因已修）**：Spring 启动后某 ApplicationRunner 失败（实例：market 每 boot 重跑 CH_V2 给 kline MODIFY TTL 触发全表物化 mutation → ClickHouse 1.8G 内存上限 OOM → `Application run failed`）→ context 关闭但 JVM 非守护线程存活 → 容器 `running`/RestartCount=0 **看似健康实际不监听端口** → gateway 报 `Connection refused <该容器当前 IP>` + 熔断 504。识别手段：`docker compose logs <svc> | grep -E "run failed|GracefulShutdown"`；根因已修（commit `6ac99f43`，CH 脚本去物化），但「Started ≠ 存活」的验收标准（§0.3）永久有效。重启后端容器一律 `up -d --force-recreate`，禁止 `restart`。
2. **任何后端容器重启/重建后必须 `docker compose restart edge`**（edge nginx 缓存后端容器 IP；部署脚本自带，手动操作最易漏）。**若 gateway 日志出现 `Connection refused <后端旧 IP>`（后端重建换 IP 而 gateway 连接/解析缓存未刷新），force-recreate gateway 再 restart edge。**
3. **直改 mysql 后注意各服务缓存刷新机制**：如 market SymbolSpec 仅「服务启动全量 warmup + admin mapping CRUD afterCommit」两条刷新路径，直改 `t_symbol_quote_mapping` 后必须 force-recreate market-service 才会重发布 redis `falconx:market:symbol-spec:*`；trading 侧再叠 10s 本地缓存。
4. **运维数据操作前先备份受影响行**（SELECT 导出 tsv 落 `logs/`），操作语句与备份路径记入 [`当前开发计划 §1`](当前开发计划.md) 对应条目。

### 0.3 部署/修复验收标准（两次误报教训后的硬标准）

- 「容器 running」「匿名请求 401」**都不算数**。后端必须看齐：`Started ... Application` + warmup 完成日志 + RestartCount=0 + 本地↔服务器**镜像 ID 一致** + flyway 版本符合预期。
- **业务验证必须带认证走公网全链**（Cloudflare→edge→gateway→服务）：demo 测试账号见 [`IDEA本地完整启动命令.md §2`](IDEA本地完整启动命令.md)（客户端账号已同步 demo），login 取 token 后 curl 业务接口。
- **前端显示类改动必须验证渲染层**：① 核对页面 `<script src>` hash 与容器内 `dist/assets` 一致（浏览器会缓存旧 index.html 且旧 chunk 仍能加载成功——验证 URL 加 `?v=xxx` cache-bust）；② 读渲染组件真实数据比对（如 K 线 hover legend 的渲染 OHLC vs 后端数据+变换，≥3 样本逐字段）。详见 [`工程经验教训`](../process/工程经验教训.md)。
- 涉及交易/风控行为的修复，用 demo 测试账号**真实下单**复现原场景验收（验完平仓清理）。

### 0.4 WSL 浏览器 QA 路径（绕 Cloudflare ECH）

WSL 内 playwright 直访 `https://app-falconx.lifebyteapp.dev` 会报 `ERR_ECH_FALLBACK_CERTIFICATE_INVALID`；IP 直访静态可用但 API 405（edge 按 Host 分流）。可复用方案——本地 Node 反代重写 Host 头（支持 WebSocket）：

```bash
mkdir -p /tmp/fx-proxy && cd /tmp/fx-proxy && npm i http-proxy --no-audit --no-fund
node -e '
const http=require("http"),hp=require("http-proxy");
const p=hp.createProxyServer({target:"http://10.143.170.189:80",ws:true});
p.on("proxyReq",r=>r.setHeader("Host","app-falconx.lifebyteapp.dev"));
p.on("proxyReqWs",r=>r.setHeader("Host","app-falconx.lifebyteapp.dev"));
const s=http.createServer((q,r)=>p.web(q,r));s.on("upgrade",(q,so,h)=>p.ws(q,so,h));s.listen(8899);' &
# 浏览器访问 http://localhost:8899/?v=<cache-bust>
```

注意：图表 crosshair 需 playwright 真实 `page.mouse.move`（合成 mousemove 不触发；多 canvas 叠层会拦 browser_hover）。

### 0.5 当前部署状态

以 [`当前开发计划 §1`](当前开发计划.md) 最新条目为真源（含部署 commit/flyway 版本/运维数据操作记录），本手册不重复维护。

### 0.6 新服务器冷启动基础数据完备性（2026-06-03 空库重放迁移实证）

**结论：仓库自含全部启动必需基础数据**——新服务器只需「clone 仓库 + 准备 secret + `deploy-to-server.sh` 全量部署」，各服务 Flyway/启动初始化自动产出以下 seed（已用空库逐文件重放 5 个 schema 的迁移与 demo 实测比对验证）：

| 来源 | 自动产出 | 基线（2026-06-03，自检脚本用 ≥） |
|---|---|---|
| compose `deploy/mysql/init` + jdbc `createDatabaseIfNotExist` | 5 个 MySQL schema | — |
| market V1-V19 | t_symbol 1581 / t_symbol_quote_mapping 1571（V19 已对齐 tier1 杠杆）/ **t_trading_hours 9424（交易时段，可交易性的前提）** / group_markup·visibility default 组全量零值 | ✓ |
| trading V1-V38 | t_symbol_leverage_tier 6311（lev×mm≤0.5）/ t_fx_pause_behavior 8 / t_notification_template 14 / t_risk_config 5 / t_risk_market_config 3 / correlation 4+26 | ✓ |
| console V1-V14 + 运行时 | 菜单 32 / SUPER_ADMIN 角色（鉴权对其直接放行）/ 权限点运行时 `@RequiresPermission` 自动注册；**superadmin 由 `DefaultSuperAdminInitializer` 首次启动自举（初始密码 `falconx-admin-init`，首登强制改密）** | ✓ |
| identity / wallet | 无需 seed（用户注册流；链 cursor/地址池运行时按 .env 密钥生成） | — |
| ClickHouse | 建库靠 compose `CLICKHOUSE_DB`（**仅新数据卷生效**，见 §9 已知约束）；表由 market 启动跑 `CH_V1-V3`（已修为真幂等零重负载，见工程经验教训 L6） | ✓ |
| Kafka / Redis | topic 自动创建 / 缓存全部 warmup 重建 | — |
| `tools/{lp,wallet}-truststore.p12` | 已入库 | — |

**自检**：部署后跑 `bash tools/cold-start-check.sh`（demo 实测 22 项全 PASS）。

**不在仓库、换服务器需人工迁移的两类**：

1. **secret（.env 真实值）**：仓库 `.env` 为本地开发/测试网占位值，键清单即模板——`FALCONX_MARKET_LP_*`（LP 行情接入 10 键）/ `FALCONX_INTERNAL_API_TOKEN` / 各服务 DB 账密 / `ALCHEMY_*` RPC / `FALCONX_WALLET_{ERC20,TRC20}_PRIVATE_KEY_PEM`（热钱包私钥）。从旧服务器 `/home/ubuntu/falconx/.env` 安全迁移，**绝不入 git**。
2. **运营配置与业务数据（可选）**：管理端录入的运营配置快照在 [`docs/operations/demo-ops-config-snapshot-20260603.sql`](../operations/demo-ops-config-snapshot-20260603.sql)（swap 费率 4 / Meta-group 加点·可见性 9+9 / 默认组非零加点，REPLACE 幂等可直接恢复）；用户/订单/持仓/账本等业务数据若需保留走 mysqldump 全量迁移（§数据迁移流程）；ClickHouse K 线/quote 历史不迁，新环境从 LP 重新累积（图表历史从零开始，预期行为）。

## 1. 服务器规格与现状

| 项 | 值 |
|---|---|
| 公网 IP（用户访问入口） | `43.199.4.38` |
| 内网 IP（SSH 跳板） | `10.143.170.189` |
| 操作系统 | Ubuntu 26.04 LTS |
| 规格 | 16 vCPU / 30 GB RAM / 193 GB disk |
| SSH | `ssh ubuntu@10.143.170.189` |
| sudo | NOPASSWD |
| Docker | 29.5.2 + Compose v5.1.4 |
| 部署目录 | `/home/ubuntu/falconx` |

## 2. 本地构建（worktree 内）

```bash
cd /home/ives/code/FalconX/.claude/worktrees/aws-deploy

# 同步主仓 jar 产物到 worktree（一次性；若主仓 jar 更新需重做）
for svc in falconx-identity-service falconx-gateway falconx-market-service \
           falconx-trading-core-service falconx-wallet-service falconx-console-service; do
  mkdir -p $svc/target
  cp /home/ives/code/FalconX/$svc/target/$svc-1.0.0-SNAPSHOT.jar $svc/target/
done

# build 镜像
docker compose -f docker-compose.prod.yml build

# 导出镜像为 tar（方便 scp）
docker save \
  falconx-identity-service:demo \
  falconx-gateway:demo \
  falconx-market-service:demo \
  falconx-trading-core-service:demo \
  falconx-wallet-service:demo \
  falconx-console-service:demo \
  falconx-frontend:demo \
  falconx-console-frontend:demo \
  falconx-edge:demo \
  -o /tmp/falconx-images.tar
```

## 3. 上传到服务器

```bash
# 镜像 tar（500-800 MB，按网络速度 1-3 分钟）
scp /tmp/falconx-images.tar ubuntu@10.143.170.189:/home/ubuntu/falconx/

# 部署描述符 + 配置
rsync -av --exclude='*/target/' --exclude='node_modules/' \
  /home/ives/code/FalconX/.claude/worktrees/aws-deploy/docker-compose.prod.yml \
  /home/ives/code/FalconX/.claude/worktrees/aws-deploy/deploy \
  ubuntu@10.143.170.189:/home/ubuntu/falconx/
```

## 4. 服务器侧启动

```bash
ssh ubuntu@10.143.170.189
cd /home/ubuntu/falconx

# 加载镜像
docker load -i falconx-images.tar

# 拉起整套
docker compose -f docker-compose.prod.yml up -d

# 等基础设施健康
docker compose -f docker-compose.prod.yml ps

# 看某服务日志
docker compose -f docker-compose.prod.yml logs -f market-service
```

## 5. 验证（烟雾测试）

```bash
# 在服务器或本机均可
PUBLIC=43.199.4.38

# 客户端前端
curl -sI http://$PUBLIC/                       # 应 200，返回 index.html
# 管理端前端
curl -sI http://$PUBLIC/admin                  # 应 200
# 业务 API（必返回 invalid request payload，证明 gateway → identity 联通）
curl -sX POST http://$PUBLIC/api/v1/auth/login -H 'Content-Type: application/json' -d '{}'
# WebSocket 入口（行情）— 用 wscat 或浏览器开发者工具
```

## 6. 访问入口

| 入口 | URL | 说明 |
|---|---|---|
| 客户端前端 | `http://43.199.4.38/` | 注册/登录/行情/交易/出金/告警 |
| 管理后台 | `http://43.199.4.38/admin` | superadmin / 你的密码 |
| API | `http://43.199.4.38/api/v1/**` | 由 edge nginx 转 gateway:18080 |
| WebSocket | `ws://43.199.4.38/ws/v1/market` / `ws/v1/trading` | gateway 代理 |
| 管理端 API | `http://43.199.4.38/admin/**` | edge 转 console-service:18085 |

## 7. AWS 安全组建议

| 入站规则 | 端口 | 来源 |
|---|---|---|
| SSH | 22 | 你的办公 IP |
| HTTP | 80 | 0.0.0.0/0 |
| HTTPS | 443 | 0.0.0.0/0（加 TLS 后开） |
| 其他（18080-18085/3306/6380/9092/8123）| | **建议关闭**，本机调试用 SSH tunnel |

## 8. 维护

```bash
# 停整套（保留数据卷）
docker compose -f docker-compose.prod.yml stop

# 重启整套
docker compose -f docker-compose.prod.yml restart

# 拉单服务（如更新 market-service）
# 1. 本地重打 jar + cp 到 worktree
# 2. docker compose build market-service
# 3. docker save -o /tmp/market.tar falconx-market-service:demo
# 4. scp + ssh + docker load + docker compose up -d market-service

# 看资源占用
docker stats --no-stream
```

## 8.1 服务启动顺序与依赖（2026-05-27 P0 事故教训）

### 事故复盘

2026-05-26 Sprint 5 用 `deploy-to-server.sh -s gateway,trading-core-service,identity-service,wallet-service,market-service,client-frontend` **并行 recreate 6 个 service** 后，客户端首页全空、trading-core 全部 REST 返回 504。

**根因（部署顺序级联失败，非代码 bug）：**

1. 6 service 并行 recreate，trading-core 启动比 gateway 快
2. trading-core 启动时 `DefaultTradingGroupMarkupService.initOnReady` 调 gateway 拉 group-markup
3. gateway 还没就绪 → `Connection refused`
4. （加固前）init `throw error` → `ApplicationReadyEvent` listener 抛异常 → Spring `Application run failed` → JVM 退出
5. 容器假 `Up` 但进程已死 → 全 REST 504 + consumer 停止消费（price.tick 积压千万级）

### 已做的加固（commit df40d74）

`DefaultTradingGroupMarkupService.initOnReady` 失败不再 fail-fast，改为降级 +
`scheduledRefresh` 每 30s 后台重试。trading-core 启动**不再硬依赖 gateway 就绪**。

### 部署纪律（必须遵守）

- **优先单服务部署**：`deploy-to-server.sh -s <single-service>`，避免一次 recreate 多个有依赖关系的服务。
- **若必须批量部署，gateway 先行**：先单独部署 gateway 并确认 `Started GatewayApplication` + health UP，再部署 trading-core / 其它业务服务。
- **部署后必验 trading-core**（最易受启动顺序影响）：
  ```bash
  # 1. 进程真活着（不能只看 docker ps 的 Up — 进程可能已死容器还在）
  curl -s http://localhost:18083/actuator/health   # 期望 {"status":"UP"}
  # 2. group-markup init 成功
  docker compose -f docker-compose.prod.yml logs --since 3m trading-core-service | grep 'group-markup.init.ready'
  # 3. gateway 路由通（401=未带 token 正常，504=trading-core 死了）
  curl -s -o /dev/null -w '%{http_code}\n' http://localhost:18080/api/v1/trading/account/summary
  # 4. consumer 有 active member（无 member = 进程死）
  docker exec falconx-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --group falconx-trading-core-service --describe | grep price.tick
  ```

### trading-core 死后恢复（含 Kafka 积压处理）

若 trading-core 死了一段时间，price.tick / kline.update 会积压（千万级过期行情）。直接重启会触发疯狂追赶 CPU 打满。恢复步骤：

```bash
# 1. stop 释放 consumer group（reset offset 要求 group 无 active member）
docker stop falconx-trading-core-service

# 2. reset 高频行情 topic offset → latest，跳过积压过期行情
#    注意：只 reset price.tick / kline.update；deposit/withdraw/kyc 等关键事件不能跳（它们 lag 本就 ~0）
docker exec falconx-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group falconx-trading-core-service --topic falconx.market.price.tick --reset-offsets --to-latest --execute
docker exec falconx-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group falconx-trading-core-service --topic falconx.market.kline.update --reset-offsets --to-latest --execute

# 3. 确认 gateway 已就绪后再启动 trading-core
docker start falconx-trading-core-service

# 4. 按上面"部署后必验 trading-core"四步验证
```

## 9. 已知约束（演示档）

1. **LP 白名单**：服务器公网 IP `43.199.4.38` 必须在 LP 侧已报备（用户已确认 OK）
2. **wallet 链**：仅接 Sepolia testnet，主网未启用
3. **wallet 链上扫描间隔 1h**：避免空扫消耗 RPC quota；要测真链入金需调小
4. **trading-core quote-ttl/stale 1d**：避免本地 stale，生产应缩短
5. **MySQL/Redis/ClickHouse 默认密码弱**：root/root 和 default/falconx，**生产必须改**
6. **未配 TLS**：当前只 HTTP，wallet 私钥相关请求建议补 HTTPS
7. **数据卷在主机**：删除 EC2 实例数据会丢，**未配 EBS snapshot 备份**

## 10. 域名 + HTTPS 后续步骤（用户操作）

1. DNS A 记录指向 `43.199.4.38`
2. 在 `nginx-edge.conf` 增加 server_name + listen 443 ssl
3. 用 certbot 申请 Let's Encrypt（可直接装 nginx-certbot 镜像）：
   ```bash
   sudo apt install certbot
   sudo certbot certonly --standalone -d falconx-demo.example.com
   # 证书在 /etc/letsencrypt/live/...，挂进 edge 容器
   ```
