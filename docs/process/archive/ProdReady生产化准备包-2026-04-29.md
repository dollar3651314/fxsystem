# PROD-READY-01 生产化准备包归档

归档日期：2026-04-29

任务编号：`PROD-READY-01`

当前结论：未完成生产化准备，当前系统仍不能写成“生产可用”。

## 1. 本轮已处理事项

- 读取 `docs/LP/SOCKET-API.pdf`、`docs/LP/签名算法.pdf`、`docs/LP/K线API.pdf`，确认 LP Socket 与签名示例中的 `timestamp` 为 13 位 Unix 毫秒时间戳。
- 修正 `SocketIoLpMarketQuoteProvider` 的 LP 握手时间戳，从秒级改为毫秒级。
- 修正 Java `socket.io-client` 接入方式：LP 的 `/safe/socket.io/` 必须通过 `IO.Options.path` 传入，传给 `IO.socket(...)` 的 URI 只保留根路径与签名 query，避免客户端把 `/safe/socket.io/` 当作 namespace 并回退到默认 `/socket.io`。
- 为 LP Socket.IO 连接失败日志补充脱敏诊断：保留异常类型、HTTP/TLS cause，隐藏 `APP-ID / signature / encrypt / nonce / timestamp` query 参数。
- 为 wallet-service 外部 RPC 日志补充脱敏：Alchemy / Infura 等 path token 不输出明文；Web3j WebSocket 第三方客户端日志在 wallet-service 默认关闭，测试环境也关闭该 logger。
- 钱包外部真节点测试补充 Alchemy HTTPS 真实节点证据：已形成 ETH 原生币入金 `DETECTED / CONFIRMING / CONFIRMED`、`wallet.deposit.detected / confirmed` outbox 与回滚 `REVERSED` 证据。
- `FX-057` 已完成：wallet-service EVM ERC20 入金识别主路径从整块逐交易 receipt 扫描改为按平台地址 indexed topic 与 `Transfer` topic 执行 `eth_getLogs` 查询，降低 Ethereum Mainnet 确认窗口重扫耗时。
- 同步 `docs/market/LP自建行情源接入契约.md` 的 13 位毫秒时间戳口径。
- 追加修正 LP Socket.IO 当前线上握手：按 app token 加密模式生成 `signature / encrypt / nonce / timestamp`，默认 path 使用 `/safe/socket.io/`，订阅 payload 显式发送当前账号实时 `serverId=17`。

## 2. LP 外部真源证据

### 2.1 环境与参数

- 本地 `.env` 存在 LP 必填键：`domain / APP-ID / token / secretKey / serverId / metaTradeVersion / metaTraderId`。
- 归档中不记录密钥明文、签名、加密串或完整握手 URL。
- 本地基础设施：MySQL、Redis、Kafka、ClickHouse 可用；Redis 当前仍连接本机 `6379` 上已有实例。

### 2.2 运行证据

执行 `market-service` 临时启动探测后，服务可启动并从 owner `t_symbol.status=1` 解析 130 个订阅品种：

```text
market.feed.bootstrap.symbols resolvedCount=130
market.lp.provider.connecting domain=wss://vibe.zerogridtech.com/ symbols=[REDACTED]
```

首次探测发现秒级时间戳实现与 LP PDF 不一致。本轮修正后，协议层测试已固定 13 位毫秒时间戳。

修复后短窗口诊断显示 LP 域名可达，但本机 JVM 默认 trust store 不信任当前网络呈现的证书链：

```text
market.lp.provider.connect.failed reason=EngineIOException: websocket error cause=SSLHandshakeException: (certificate_unknown) PKIX path building failed
```

公开证书链探测结果：

```text
subject=CN=vibe.zerogridtech.com
issuer=C=AU, ST=Sydney, O=LIFEBYTE SYSTEMS PTY LTD, CN=LifeByte IT Management Root Certificate Authority
Verify return code: 0 (ok)
```

用户确认本轮可继续使用 `/tmp/falconx-combined-truststore.p12` 作为定位与归档证据。使用该临时 trust store 追加本机网络根证书，并修正 Java Socket.IO path 后，再次启动 `market-service`，已取得连接与订阅证据：

```text
market.lp.provider.connected=1
market.lp.provider.subscribed=1
market.lp.provider.price-compression.parsed quotes>0 accepted>0=0
market.quote.received source=LP_SOCKET=0
market.redis.written=0
market.event.publish.completed=0
market.lp.provider.connect.failed=0
```

关键日志：

```text
market.feed.bootstrap.symbols resolvedCount=130 symbols=[REDACTED]
market.lp.provider.connecting domain=wss://vibe.zerogridtech.com/ symbols=[REDACTED]
market.lp.provider.connected domain=wss://vibe.zerogridtech.com/
market.lp.provider.subscribed serverId=20 symbols=[REDACTED]
market.lp.provider.disconnected reason=io client disconnect
```

用户随后确认 LP 账号已开通实时行情权限，指定改用 `serverId=5`，并要求覆盖 `XAUUSD / BTCUSD / EURUSD`。本地 owner 表中 `XAUUSD=1`、`EURUSD=1`、`BTCUSD=2`；为完成探测，临时将本地 `BTCUSD` 置为 `status=1`，探测结束后已恢复为 `status=2`。

使用 `FALCONX_MARKET_LP_SERVER_ID=5`、`/tmp/falconx-combined-truststore.p12` 与临时启用后的 owner 白名单再次启动 `market-service`，4 分钟窗口内结果如下：

```text
market.lp.provider.connected=1
market.lp.provider.subscribed serverId=5=1
market.lp.provider.price-compression.parsed=0
market.quote.received source=LP_SOCKET=0
market.redis.written=0
market.event.publish.completed=0
market.lp.provider.connect.failed=0
```

关键日志：

```text
market.feed.bootstrap.symbols resolvedCount=131 ... BTCUSD
market.lp.provider.connected domain=wss://vibe.zerogridtech.com/
market.lp.provider.subscribed serverId=5 ... XAUUSD ... BTCUSD ... EURUSD
market.lp.provider.disconnected reason=io client disconnect
```

继续对照 `docs/LP/SOCKET-API.pdf`、`docs/LP/签名算法.pdf` 与 `/Users/ives/WorkCode/WorkSpace/unicorn-socket` 服务端代码后，确认当前线上 Socket.IO 握手必须使用 app token 加密模式。使用当前 `.env` 参数和 `/tmp/falconx-combined-truststore.p12` 再次探测：

```text
旧空 Authorization 握手：HTTP 401
app token 加密握手 + 显式 serverId=20：connected/subscribed，但 10 秒无报价
app token 加密握手 + 显式 serverId=5：connected/subscribed，但 10 秒无报价
app token 加密握手 + 不传 serverId：立即收到 price-compression，返回 serverId=17
app token 加密握手 + 显式 serverId=17：立即收到 LP_SOCKET 报价
```

原始探针不传 `serverId` 后，LP 按 `APP-ID` 映射选择默认 server，并在 10 秒内返回 `EURUSD / BTCUSD / XAUUSD` 报价；返回帧显示实际实时 `serverId=17`。当前实现已按用户确认改为显式发送 `serverId=17`。

`market-service` 使用当前 `.env` 参数启动后，已取得以下证据：

```text
market.lp.provider.connected=1
market.lp.provider.subscribed serverId=17=1
market.quote.received source=LP_SOCKET=1
market.redis.written=1
market.analytics.quote.flush.completed=1
market.event.publish.completed topic=falconx.market.price.tick=1
```

### 2.3 LP 当前缺口

- LP 连接、订阅、`price-compression`、Redis、ClickHouse flush 与 Kafka publish 证据已形成；`FX-058` 当前关闭。
- 显式 `serverId=5 / 20` 会导致无报价，当前 Socket.IO 实时订阅必须显式发送 `serverId=17`。
- `/tmp/falconx-combined-truststore.p12` 只能作为本轮定位证据，不能作为长期生产 trust store 治理结论。

## 3. Wallet 外部真节点证据

### 3.1 环境与参数

- 用户已将 Alchemy Ethereum Mainnet 信息补充进本地 `.env`。
- 本轮命令只通过环境变量读取 `.env`，归档中不记录 RPC token、完整 URL 或签名类敏感信息。
- 用户确认本轮可继续使用 `/tmp/falconx-combined-truststore.p12` 作为外部证据 trust store。

### 3.2 失败路径与日志脱敏证据

执行外部真节点失败路径用例：

```bash
JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/tmp/falconx-combined-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit" \
FALCONX_WALLET_EXTERNAL_TEST_ENABLED=true \
FALCONX_WALLET_ETH_RPC_URL="<从 .env 读取的 Alchemy HTTPS URL>" \
mvn -pl falconx-wallet-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=Web3jChainDepositListenerExternalFailureIntegrationTests test
```

结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
wallet.listener.chainHead.syncFailed ... rpcUrl=https://eth-mainnet.g.alchemy.com/v2/[REDACTED]
```

结论：错误认证路径会重试且不推进 owner 游标；日志只输出脱敏 RPC URL。

### 3.3 ETH 真实节点入金发现与确认推进证据

执行 Alchemy HTTPS 真实节点入金发现用例：

```bash
JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/tmp/falconx-combined-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit" \
FALCONX_WALLET_EXTERNAL_TEST_ENABLED=true \
FALCONX_WALLET_ETH_RPC_URL="<从 .env 读取的 Alchemy HTTPS URL>" \
mvn -pl falconx-wallet-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=WalletExternalChainNodeAutomationIntegrationTests#shouldTrackRealEthDepositAcrossCursorRescanAndPersistWalletTxId test
```

结果：通过（1 test，约 38.57 秒）。

关键证据：

```text
wallet.deposit.observed chain=ETH token=ETH ...
wallet.deposit.tracked status=CONFIRMING userId=98001
wallet.listener.deposit.detected chain=ETH ... token=ETH confirmations=1 amount=0.02121109
wallet.event.publish.completed topic=falconx.wallet.deposit.detected
wallet.deposit.tracked chain=ETH ... status=CONFIRMED userId=98001
wallet.listener.deposit.detected chain=ETH ... token=ETH confirmations=4 amount=0.02121109
wallet.event.publish.completed topic=falconx.wallet.deposit.confirmed
wallet.listener.chainHead.synced chain=ETH ... rpcUrl=https://eth-mainnet.g.alchemy.com/v2/[REDACTED]
```

结论：已取得 Alchemy HTTPS 真节点扫块、平台地址命中、`t_wallet_deposit_tx` 从 `CONFIRMING` 推进到 `CONFIRMED`、`wallet.deposit.detected / confirmed` outbox 写入的证据；日志中的 RPC URL 仅保留脱敏路径。

### 3.4 ETH 真实节点回滚撤回证据

执行 Alchemy HTTPS 真实节点回滚撤回用例：

```bash
JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/tmp/falconx-combined-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit" \
FALCONX_WALLET_EXTERNAL_TEST_ENABLED=true \
FALCONX_WALLET_ETH_RPC_URL="<从 .env 读取的 Alchemy HTTPS URL>" \
mvn -pl falconx-wallet-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=WalletExternalChainNodeAutomationIntegrationTests#shouldEmitReversalWhenConfirmedTransactionDisappearsFromRealRescanWindow test
```

结果：通过（1 test，约 8.67 秒）。

关键证据：

```text
wallet.deposit.tracked ... status=REVERSED userId=98002
wallet.listener.deposit.reversedDetected ... rpcUrl=https://eth-mainnet.g.alchemy.com/v2/[REDACTED]
wallet.listener.chainHead.synced chain=ETH ... scannedBlocks=3 reversedCount=1
```

结论：已取得已确认入金在回扫窗口消失后进入 `REVERSED` 并写出 `wallet.deposit.reversed` outbox 的证据。

### 3.5 Wallet 当前限制

- `FX-057` 已消除 ERC20 主路径逐交易 receipt 扫描：当前实现保留 ETH 原生币直接转账识别，ERC20 入金识别改为面向平台地址和 `Transfer` topic 的 `eth_getLogs` 查询。
- 本轮证据使用 HTTPS RPC；WSS 连接在默认 JVM trust store 下曾出现 PKIX 失败，且第三方 Web3j logger 存在输出原始 URL 风险。本轮已关闭该 logger 并补充自有日志脱敏，但 WSS 真节点正向证据尚未形成。
- `/tmp/falconx-combined-truststore.p12` 只能作为本轮定位证据，不能替代正式生产 trust store、证书轮换和密钥治理方案。

## 4. 本地回归验证

以下 Maven 命令均串行执行：

```bash
mvn -pl falconx-market-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SocketIoLpMarketQuoteProviderTests,LpWebSocketProtocolSupportTests test
```

结果：13 tests，通过。

```bash
mvn -pl falconx-market-service -am test
```

结果：74 tests，通过；2 skipped。

```bash
mvn -pl falconx-trading-core-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TradingKafkaMarketEventIntegrationTests,TradingQuoteSnapshotStaleIntegrationTests test
```

结果：5 tests，通过。

```bash
mvn -pl falconx-wallet-service -am test
```

结果：32 tests，通过；3 skipped（外部门禁未开启时跳过外部真节点正向用例）。

```bash
JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/tmp/falconx-combined-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit" \
FALCONX_WALLET_EXTERNAL_TEST_ENABLED=true \
FALCONX_WALLET_ETH_RPC_URL="<从 .env 读取的 Alchemy HTTPS URL>" \
mvn -pl falconx-wallet-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=Web3jChainDepositListenerExternalFailureIntegrationTests test
```

结果：1 test，通过。

```bash
JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/tmp/falconx-combined-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit" \
FALCONX_WALLET_EXTERNAL_TEST_ENABLED=true \
FALCONX_WALLET_ETH_RPC_URL="<从 .env 读取的 Alchemy HTTPS URL>" \
mvn -pl falconx-wallet-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=WalletExternalChainNodeAutomationIntegrationTests#shouldTrackRealEthDepositAcrossCursorRescanAndPersistWalletTxId test
```

结果：1 test，通过；约 38.57 秒。

```bash
JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/tmp/falconx-combined-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit" \
FALCONX_WALLET_EXTERNAL_TEST_ENABLED=true \
FALCONX_WALLET_ETH_RPC_URL="<从 .env 读取的 Alchemy HTTPS URL>" \
mvn -pl falconx-wallet-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=WalletExternalChainNodeAutomationIntegrationTests#shouldEmitReversalWhenConfirmedTransactionDisappearsFromRealRescanWindow test
```

结果：1 test，通过；约 8.67 秒。

## 5. 后续必须补齐

1. 补齐 Alchemy WSS 正向证据，或正式冻结 HTTPS 为当前生产 RPC 方式；二者都必须继续保持日志脱敏。
2. 将 `/tmp/falconx-combined-truststore.p12` 替换为正式或准生产 JVM trust store 与证书轮换策略。
3. 补齐密钥治理、部署、回滚、监控、日志检索、告警与 canary 证据后，才能重新评估对外运行口径。
