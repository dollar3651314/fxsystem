# IDEA 本地完整启动命令

> 适用范围：FalconX 当前已完成的本地可验证功能。本文只记录本地开发启动命令和本地测试账号，不包含生产密钥。

## 1. 浏览器验证入口

| 入口 | 地址 | 说明 |
| --- | --- | --- |
| 客户端交易终端 | `http://localhost:5200/` | Vite 代理 `/api` 与 `/ws` 到 gateway `18080` |
| 管理端 | `http://localhost:5300/admin/login` | Vite 代理 XHR `/admin/**` 到 console-service `18085` |
| Gateway | `http://localhost:18080` | C 端 REST / WebSocket 统一入口 |

## 2. 本地登录账号

### 2.1 客户端账号

| 字段 | 值 |
| --- | --- |
| 邮箱 | `demo.20260511@falconx.local` |
| 密码 | `FalconXDemo@2026` |
| 姓名 | `Demo Trader` |
| 国籍 | `USA` |

若本地数据库被清空，启动后重新执行：

```bash
curl -sS -H 'Content-Type: application/json' \
  -d '{"email":"demo.20260511@falconx.local","password":"FalconXDemo@2026","firstName":"Demo","middleName":null,"lastName":"Trader","birthDate":"1990-01-01","nationality":"USA"}' \
  http://localhost:5200/api/v1/auth/register
```

### 2.2 管理端账号

| 字段 | 值 |
| --- | --- |
| 用户名 | `superadmin` |
| 密码 | `FalconXAdmin@2026` |
| 角色 | `SUPER_ADMIN` |

说明：当前本地库中的 `superadmin` 已重置为上面的密码。若重建数据库，console-service 默认初始密码为 `falconx-admin-init`；需要时可在本地库重新重置。

## 3. 基础设施启动

```bash
cd /Users/ives/WorkCode/Claude/FalconX
docker compose up -d
docker compose ps
```

当前 Redis 使用 `falconx-redis`，宿主机端口为 `6380 -> 6379`，不占用 `mantis-redis` 的宿主机 `6379`。

## 4. 构建命令

```bash
cd /Users/ives/WorkCode/Claude/FalconX
mvn -Dmaven.test.skip=true package
```

说明：当前用于本地启动 jar 生成。`mvn -DskipTests package` 会进入 `testCompile`，本地已发现 gateway E2E 测试支撑类存在测试 classpath 缺口，不能作为本次启动前置命令。

## 5. screen 完整启动命令

先准备日志目录：

```bash
cd /Users/ives/WorkCode/Claude/FalconX
mkdir -p logs/local
```

启动 identity-service：

```bash
screen -dmS falconx-identity bash -lc "cd /Users/ives/WorkCode/Claude/FalconX && exec java -jar falconx-identity-service/target/falconx-identity-service-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev >> logs/local/identity-service.log 2>&1"
```

启动 gateway：

```bash
screen -dmS falconx-gateway bash -lc "cd /Users/ives/WorkCode/Claude/FalconX && exec java -jar falconx-gateway/target/falconx-gateway-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev >> logs/local/gateway.log 2>&1"
```

启动 trading-core-service：

```bash
screen -dmS falconx-trading bash -lc "cd /Users/ives/WorkCode/Claude/FalconX && exec java -jar falconx-trading-core-service/target/falconx-trading-core-service-1.0.0-SNAPSHOT.jar --falconx.trading.cache.quote-ttl=1d --falconx.trading.stale.max-age=1d >> logs/local/trading-core-service.log 2>&1"
```

启动 market-service：

```bash
screen -dmS falconx-market bash -lc "cd /Users/ives/WorkCode/Claude/FalconX && exec java -jar falconx-market-service/target/falconx-market-service-1.0.0-SNAPSHOT.jar --falconx.market.analytics.quote-flush-interval=10000 >> logs/local/market-service.log 2>&1"
```

说明：当前默认使用真实 market profile，启动后会使用 `.env` 中的 LP 参数连接真实行情源，并启用 `MybatisClickHouseMarketAnalyticsWriter`。报价 tick 会先进入内存缓冲队列，ClickHouse `quote_tick` 默认每 10 秒定时批量写入一次；`quote-batch-size=200` 表示每批 INSERT 的最大记录数，运行时低于 1 会按 1 执行，高于 10000 会按 10000 执行。若显式追加 `--spring.profiles.active=stub`，market-service 会改用日志型 writer，不会真实写 ClickHouse。

启动 wallet-service：

```bash
screen -dmS falconx-wallet bash -lc "cd /Users/ives/WorkCode/Claude/FalconX && set -a && source .env && set +a && exec env FALCONX_WALLET_DB_USERNAME=root FALCONX_WALLET_DB_PASSWORD=root FALCONX_WALLET_ETH_ACCOUNT_XPUB='xpub6DB3tvxWiLh7aM2mLjwUbRfNGw65C1SAz8ayQbhsXnHgBc1gywUyrwen7cb2XJ8hDWduFZGE2AMRzJBSC2p6ci62NRdBvbtzWGvGNjTRsT5' FALCONX_WALLET_TRON_ACCOUNT_XPUB='xpub6DL73xExtJREFmDHsmeqTiWA3hiSAw3JirYQcsbaN3tMdpRDW3rqXQymJChEJpfEgBZkDe7d5VQe7N1Y9PaxcicPobqaDAzy9habuhAzGXN' java -jar falconx-wallet-service/target/falconx-wallet-service-1.0.0-SNAPSHOT.jar --falconx.wallet.chains.eth.scan-interval=5m --falconx.wallet.chains.bsc.scan-interval=5m --falconx.wallet.chains.tron.scan-interval=5m --falconx.wallet.chains.sol.scan-interval=5m >> logs/local/wallet-service.log 2>&1"
```

说明：上面的 xpub 仅用于本地开发地址派生验证，不是可花费密钥。若 `.env` 未配置真实链 RPC，链上扫描会出现连接告警；入金地址分配接口仍可验证。若使用 TRC20 测试网监听，`ALCHEMY_ETHEREUM_NILETEST_HTTPS` 必须是 TRON HTTP API 基础地址，wallet-service 会请求 `/wallet/getnowblock` 与 `/walletsolidity/gettransactioninfobyblocknum`。

启动 console-service：

```bash
screen -dmS falconx-console bash -lc "cd /Users/ives/WorkCode/Claude/FalconX && exec java -jar falconx-console-service/target/falconx-console-service-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev >> logs/local/console-service.log 2>&1"
```

启动客户端前端：

```bash
screen -dmS falconx-frontend bash -lc "cd /Users/ives/WorkCode/Claude/FalconX/falconx-frontend && exec npm run dev -- --host 0.0.0.0 >> ../logs/local/falconx-frontend.log 2>&1"
```

启动管理端前端：

```bash
screen -dmS falconx-console-frontend bash -lc "cd /Users/ives/WorkCode/Claude/FalconX/falconx-console-frontend && exec npm run dev -- --host 0.0.0.0 >> ../logs/local/falconx-console-frontend.log 2>&1"
```

检查进程：

```bash
screen -ls
lsof -nP -iTCP:18080 -iTCP:18081 -iTCP:18082 -iTCP:18083 -iTCP:18084 -iTCP:18085 -iTCP:5200 -iTCP:5300 -sTCP:LISTEN
```

停止 screen 服务：

```bash
screen -S falconx-identity -X quit
screen -S falconx-gateway -X quit
screen -S falconx-trading -X quit
screen -S falconx-market -X quit
screen -S falconx-wallet -X quit
screen -S falconx-console -X quit
screen -S falconx-frontend -X quit
screen -S falconx-console-frontend -X quit
```

## 6. IDEA Run Configuration 参考

工作目录统一设置为：

```text
/Users/ives/WorkCode/Claude/FalconX
```

| 名称 | 类型 | Main class / 命令 | Program arguments |
| --- | --- | --- | --- |
| FalconX Identity | Application | `com.falconx.identity.IdentityServiceApplication` | `--spring.profiles.active=dev` |
| FalconX Gateway | Application | `com.falconx.gateway.GatewayApplication` | `--spring.profiles.active=dev` |
| FalconX Trading Core | Application | `com.falconx.trading.TradingCoreServiceApplication` | `--falconx.trading.cache.quote-ttl=1d --falconx.trading.stale.max-age=1d` |
| FalconX Market | Application | `com.falconx.market.MarketServiceApplication` | `--falconx.market.analytics.quote-flush-interval=10000` |
| FalconX Wallet | Application | `com.falconx.wallet.WalletServiceApplication` | `--falconx.wallet.chains.eth.scan-interval=5m --falconx.wallet.chains.bsc.scan-interval=5m --falconx.wallet.chains.tron.scan-interval=5m --falconx.wallet.chains.sol.scan-interval=5m` |
| FalconX Console | Application | `com.falconx.console.ConsoleServiceApplication` | `--spring.profiles.active=dev` |
| FalconX Frontend | npm | `falconx-frontend/package.json` script `dev` | `-- --host 0.0.0.0` |
| FalconX Console Frontend | npm | `falconx-console-frontend/package.json` script `dev` | `-- --host 0.0.0.0` |

Wallet 的 IDEA Environment variables：

```text
FALCONX_WALLET_DB_USERNAME=root;FALCONX_WALLET_DB_PASSWORD=root;FALCONX_WALLET_ETH_ACCOUNT_XPUB=xpub6DB3tvxWiLh7aM2mLjwUbRfNGw65C1SAz8ayQbhsXnHgBc1gywUyrwen7cb2XJ8hDWduFZGE2AMRzJBSC2p6ci62NRdBvbtzWGvGNjTRsT5;FALCONX_WALLET_TRON_ACCOUNT_XPUB=xpub6DL73xExtJREFmDHsmeqTiWA3hiSAw3JirYQcsbaN3tMdpRDW3rqXQymJChEJpfEgBZkDe7d5VQe7N1Y9PaxcicPobqaDAzy9habuhAzGXN
```

其余 Java 服务依赖根目录 `.env` 与 `application.yml`，若 IDEA 未自动读取 `.env`，把 `.env` 中对应 `FALCONX_*_DB_USERNAME / FALCONX_*_DB_PASSWORD` 手动加入该服务的 Environment variables。
Wallet 还依赖 `.env` 中的 `FALCONX_WALLET_ERC20_USDC_CONTRACT`、`FALCONX_WALLET_ERC20_USDT_CONTRACT`、
`ALCHEMY_ETHEREUM_NILETEST_HTTPS`、`FALCONX_WALLET_TRC20_USDT_CONTRACT`，IDEA 若未加载 `.env` 需一并补入。
TRC20 监听首次看到初始游标 `0` 时会把游标基线推进到当前最新块，不回扫历史入金；基线之后的 TRC20 USDT 入金按 `--falconx.wallet.chains.tron.scan-interval=5m` 拉取。

## 7. 本地验证命令

客户端登录：

```bash
curl -sS -H 'Content-Type: application/json' \
  -d '{"email":"demo.20260511@falconx.local","password":"FalconXDemo@2026"}' \
  http://localhost:5200/api/v1/auth/login
```

管理端登录：

```bash
curl -sS -H 'Content-Type: application/json' \
  -d '{"username":"superadmin","password":"FalconXAdmin@2026"}' \
  http://localhost:5300/admin/auth/login
```

客户端行情与资料验证：

```bash
ACCESS_TOKEN=$(curl -sS -H 'Content-Type: application/json' -d '{"email":"demo.20260511@falconx.local","password":"FalconXDemo@2026"}' http://localhost:5200/api/v1/auth/login | jq -r '.data.accessToken')
curl -sS -H "Authorization: Bearer ${ACCESS_TOKEN}" http://localhost:5200/api/v1/me/profile
curl -sS -H "Authorization: Bearer ${ACCESS_TOKEN}" http://localhost:5200/api/v1/market/symbols
curl -sS -H "Authorization: Bearer ${ACCESS_TOKEN}" 'http://localhost:5200/api/v1/market/klines/XAUUSD?interval=1m&limit=5'
```

交易账户与钱包地址验证：

```bash
ACCESS_TOKEN=$(curl -sS -H 'Content-Type: application/json' -d '{"email":"demo.20260511@falconx.local","password":"FalconXDemo@2026"}' http://localhost:5200/api/v1/auth/login | jq -r '.data.accessToken')
curl -sS -H "Authorization: Bearer ${ACCESS_TOKEN}" http://localhost:18080/api/v1/trading/accounts/me
curl -sS -X POST -H "Authorization: Bearer ${ACCESS_TOKEN}" -H 'Content-Type: application/json' http://localhost:18080/api/v1/wallet/deposit-addresses/ensure
```

管理端受保护接口验证：

```bash
ADMIN_TOKEN=$(curl -sS -H 'Content-Type: application/json' -d '{"username":"superadmin","password":"FalconXAdmin@2026"}' http://localhost:5300/admin/auth/login | jq -r '.data.accessToken')
curl -sS -H "Authorization: Bearer ${ADMIN_TOKEN}" http://localhost:5300/admin/me
curl -sS -H "Authorization: Bearer ${ADMIN_TOKEN}" 'http://localhost:5300/admin/customers?page=0&size=5'
curl -sS -H "Authorization: Bearer ${ADMIN_TOKEN}" 'http://localhost:5300/admin/symbols?page=0&size=5'
```

## 8. 当前已验证结果

- `docker compose ps`：MySQL、Redis、Kafka、ClickHouse 均为 healthy。
- `mvn -Dmaven.test.skip=true package`：14 个 Maven 模块打包通过。
- 客户端页面 `http://localhost:5200/`：HTTP 200。
- 管理端页面 `http://localhost:5300/admin/login`：浏览器 HTML 请求 HTTP 200。
- 客户端账号注册、登录、资料查询、行情 symbols、K 线、WebSocket `price.tick` 已通过。
- `gateway -> trading-core` 的 `/api/v1/trading/accounts/me` 已通过。
- `gateway -> wallet-service` 的 `/api/v1/wallet/deposit-addresses/ensure` 已通过，返回 `TRC20` 与 `ERC20` 两个网络地址。
- 管理端登录、`/admin/me`、客户列表、品种列表已通过。

## 9. 当前限制

- market-service 默认按真实 market profile 启动，依赖 `.env` 中的 LP 参数、ClickHouse、Redis、Kafka 与 MySQL；如需纯本地 mock 行情演示，可临时追加 `--spring.profiles.active=stub`，但该模式不会写 ClickHouse。
- wallet-service 若已配置 `.env` 中的 ETH / TRC20 测试网 RPC 和合约地址，可启动链上监听；未配置真实链 RPC 时只验证地址派生与接口链路，不代表链上确认监听已在本地打通。
- 本文启动方式是本地开发验证口径，不作为生产部署说明。
