# LP 自建行情源接入契约

## 1. 当前结论

FalconX v1 当前实时行情唯一生产来源固定为自建 LP。

owner 固定为：

- `falconx-market-service`

运行时链路固定为：

```text
LP Socket.IO price-compression
  -> SocketIoLpMarketQuoteProvider
  -> LpWebSocketProtocolSupport
  -> MarketQuoteMappingService（源 symbol -> 平台 symbol，可配置乘数与加点）
  -> QuoteStandardizationService
  -> MarketTradingScheduleGuardService / MarketQuoteQualityGuardService
  -> Redis 最新价
  -> ClickHouse quote_tick / kline
  -> Kafka falconx.market.price.tick / falconx.market.kline.update
  -> 北向 ws://{host}/ws/v1/market
```

当前标准报价 `source` 固定为：

- `TM_QUOTE`

旧外部行情源运行时代码、配置项、协议解析和测试入口已删除，不再作为回滚路径保留。

## 2. 配置

`market-service` 支持两类配置来源：

- 标准部署环境变量：`FALCONX_MARKET_LP_*`
- 本地根目录 `.env`：兼容 LP 文档里的 `domain / APP-ID / token / secretKey / serverId / metaTradeVersion / metaTraderId`。其中 LP 文档的 `serverId` 只作为历史 K 线 API 的服务器 ID 兼容别名，不作为 Socket.IO 实时订阅 server；实时订阅 server 默认固定为 `17`，仅允许通过 `FALCONX_MARKET_LP_SERVER_ID` 显式覆盖。

运行时配置项：

| 配置项 | 说明 |
| --- | --- |
| `falconx.market.lp.enabled` | 是否启用 LP provider |
| `falconx.market.lp.code` | LP 代码，默认 `GODSA`；用于筛选 `t_symbol_quote_mapping.source_lp_code` 与 `t_symbol.lp_code` |
| `falconx.market.lp.domain` | LP Socket.IO 服务域名或根地址，例如 `wss://host` |
| `falconx.market.lp.socket-path` | LP Socket.IO Engine.IO path，默认 `/safe/socket.io/` |
| `falconx.market.lp.app-id` | LP `APP-ID` |
| `falconx.market.lp.token` | LP app token，参与 Socket.IO `encrypt` 与 `signature` 生成 |
| `falconx.market.lp.secret-key` | LP AES key，参与 Socket.IO `encrypt` 生成 |
| `falconx.market.lp.server-id` | Socket.IO 实时订阅显式发送的 LP server；当前账号使用 `17`，默认值为 `17` |
| `falconx.market.lp.meta-trade-version` | K 线 API 使用的 MT 版本，MT4=4，MT5=5 |
| `falconx.market.lp.meta-trader-id` | K 线 API 使用的服务器 ID |
| `falconx.market.lp.connect-timeout` | LP 连接超时 |
| `falconx.market.lp.reconnect-interval` | 断线重连间隔 |
| `falconx.market.lp.symbol-whitelist-refresh-cron` | owner symbol 白名单刷新 cron |
| `falconx.market.lp.symbol-whitelist-refresh-zone` | owner symbol 白名单刷新时区 |
| `falconx.market.stale.max-age` | 报价时间与当前时间允许的最大偏移，默认 `5s` |
| `falconx.market.quote-quality.unchanged-max-age` | 同一 `symbol` 的 `bid / ask` 连续不变多久后标记为 `NO_QUOTE`，默认 `1m` |

禁止把 LP 密钥写入仓库。`.env` 已被 `.gitignore` 排除。

## 3. WebSocket 握手

LP Socket.IO 地址格式：

```text
wss://{domain}/safe/socket.io/?APP-ID={appId}&signature={signature}&encrypt={encrypt}&EIO=4&socketSource=1&socketVersion=1.1&nonce={nonce}&timestamp={timestamp}
```

当前 Socket.IO 行情握手以 `docs/LP/SOCKET-API.pdf` 和 `docs/LP/签名算法.pdf` 为准：

- `APP-ID={appId}`
- `signature=sha1(sort(token, timestamp, nonce, encrypt))`
- `encrypt=Base64(AES-CBC-PKCS7(random16 + msgLen4 + token + appId))`
- `EIO=4`
- `socketSource=1`
- `socketVersion=1.1`
- `nonce` 为随机值
- `timestamp` 为当前毫秒时间戳
- 不再发送 `Authorization=` 空值握手

Java `socket.io-client` 接入规则：

- 默认使用 `IO.Options.path = "/safe/socket.io/"`。
- `IO.socket(...)` 的 URI 只保留 `scheme://host:port/`，query 通过 `IO.Options.query` 传入。
- `IO.Options.transports` 固定为 `websocket`，由客户端生成 Engine.IO `transport=websocket` 握手参数。

## 4. 订阅

连接成功后发送 Socket.IO event：

```text
external-sub-symbol
```

payload：

```json
{
  "serverId": 17,
  "symbolList": ["XAUUSD", "BTCUSD", "EURUSD"]
}
```

Socket.IO 实时订阅必须显式发送当前账号可用的实时 server：`serverId=17`。历史定位中，不传 `serverId` 时 LP 会按 `APP-ID` 映射到 `17`；本轮已按用户确认改为显式传递 `17`。显式发送 `serverId=5` 或 `serverId=20` 时，连接与订阅事件成立，但 10 秒内无 `price-compression` 推送。因此运行配置不得把 LP 文档用于历史 API 的 `serverId=20` 误映射到 `falconx.market.lp.server-id`。

`external-sub-symbol` 的 event data 必须以 JSON 字符串发送。对接方 `netty-socketio` 监听器按 `String.class` 注册，并在服务端自行反序列化为 `ExternalMsg`；不得把 payload 转成 Socket.IO JSON object 后发送，否则服务端不会进入第三方订阅处理链路。

`symbolList` 必须来自 market owner `t_symbol_quote_mapping.enabled=1 AND lp_subscribe_enabled=1` 去重后的当前 LP `source_symbol`，且 `(source_lp_code, source_symbol)` 必须存在于 `t_symbol.lp_code + t_symbol.symbol` 并满足 `t_symbol.status=1`，不得在运行时代码中硬编码。当前 `t_symbol` 由 LP MT5 快照 `/Users/ives/Desktop/mt5_symbols.csv` 生成：`V5` 先导入 `1839` 条 LP symbol，`V10` 曾按当时快照口径删除 `.p / .c / .f` 后缀变体，最终保留 `1581` 条 LP 源 symbol，其中 `1571` 条为 `status=1`；V17 起存量源默认 `lp_code=GODSA`，后续多个 LP 可维护相同 `symbol`。后续运行时和管理端接口不再按后缀做特殊限制，LP 订阅资格统一由 `source_lp_code`、`t_symbol.status`、`t_symbol_quote_mapping.enabled` 和 `lp_subscribe_enabled` 决定；系统级 `category / market_code / price_precision / qty_precision` 由 `t_symbol_quote_mapping` 配置，`t_symbol` 只保留上游源元数据。

Swap 费率与交易时段属于 FalconX 系统 Symbol 配置链路，不属于 LP 源 symbol 元数据。`t_swap_rate.symbol`、`t_trading_hours.symbol` 和 `t_trading_hours_exception.symbol` 必须使用 `t_symbol_quote_mapping.platform_symbol`；交易节假日通过该 mapping 的 `market_code` 解析。管理端只能从报价映射行进入 Swap 和交易时段配置/查看，避免把 LP `source_symbol` 误写成系统维度。

`lp_subscribe_enabled` 是平台 symbol 级的 LP 订阅开关，默认 `1` 保持现有订阅行为。后续管理后台关闭某个映射后，该平台 symbol 不再接收对应 LP 源报价；如果多个平台 symbol 映射到同一个 `source_lp_code + source_symbol`，只要仍有任一启用、开启订阅且源 symbol 仍为 `t_symbol.status=1`，该 `source_symbol` 仍会保留在对应上游 LP 订阅集合中。

平台自定义 symbol 通过 `t_symbol_quote_mapping` 映射到 LP 源 symbol。计算规则固定为：

- `platformBid = sourceBid * priceMultiplier + bidAdjustment`
- `platformAsk = sourceAsk * priceMultiplier + askAdjustment`
- `mid / mark = (platformBid + platformAsk) / 2`

示例：`XAU100` 可配置为 `source_lp_code=GODSA`、`source_symbol=XAUUSD`、`price_multiplier=100`；`AAAUSD` 可配置为 `source_lp_code=GODSA`、`source_symbol=XAUUSD` 并叠加自己的绝对加点。

LP symbol 必须按 MT5 原始代码传递和过滤，保留 `.` 与后缀大小写，例如 `AAPL.NAS`、`SPCUSD.risk`。订阅构造、报价解析和本地白名单匹配都不得把这些 symbol 规整为纯字母数字，否则会订阅不存在的代码。系统不得按 `.p / .c / .f` 或其他后缀写特殊拦截逻辑；如果后续数据库中存在这类命名，是否接入报价只看三表数据、启停状态和 LP 是否实际推送。

当 `t_symbol.status` 或 mapping 订阅开关变化后，`MarketFeedBootstrapRunner` 按配置周期刷新 provider 订阅和本地过滤集合。数据库临时不可用时，provider 保留上一轮白名单，不清空运行中行情范围。

## 5. 报价解析

LP 实时报价 event：

```text
price-compression
```

处理规则：

1. 回调线程只做解压、解析和入队，不直接执行 Redis / Kafka / DB / ClickHouse 主链路。
2. 若二进制 payload 首字节为 Socket.IO binary header `0x04`，先移除该字节。
3. 对剩余字节执行 Snappy 解压。
4. 解析 JSON 中的 `symbol + bid + ask + timestamp`，确认报价字段完整；Socket.IO 实时链路分发进入接入主链路前，以 market-service 当前 UTC 时间写入 `ExternalRawQuote.ts`，避免 LP 源时间稳定漂移或大量 symbol 单线程排队导致实时帧被误判为 stale。
5. 只允许 owner 映射快照内已开启 LP 订阅的源 symbol 进入后续链路，并在进入标准化前转换为一个或多个平台 symbol 报价。

当前解析兼容字段：

| 语义 | 字段 |
| --- | --- |
| symbol | `symbol / ticker / instrument / Symbol / Ticker` |
| bid | `bid / bidPrice / Bid / BidPrice` |
| ask | `ask / askPrice / Ask / AskPrice` |
| timestamp | `receivingTime / datetimeUtc / datetime / ts / timestamp / time / quoteTimestamp / ctm / ctmUtc / Time` |

缺少 `bid` 或 `ask` 的消息不得进入标准报价链路。

实时 Socket.IO 帧的北向 `ts` 表示 FalconX 平台接入主链路处理 tick 的 UTC 时间；LP payload 中的源时间只用于解析校验，不作为交易终端 K 线聚合和 WebSocket 推送的时间轴。离线解析和测试可继续使用 LP 源时间。

## 6. 行情质量与休盘处理

报价质量 owner 固定为 `market-service`，当前质量状态为：

| 状态 | 触发条件 | 处理动作 |
| --- | --- | --- |
| `FRESH` | 报价通过时间、价格和交易日历检查 | 写 Redis 最新价、ClickHouse、Kafka、WebSocket 和 K 线聚合，可被 trading-core 二次校验后用于成交 |
| `STALE` | `abs(now - quote.ts) > falconx.market.stale.max-age`，默认 `5s` | 只发布 `falconx.market.price.tick` 不可成交快照，不刷新可成交 Redis 最新价、ClickHouse、WebSocket 或 K 线；trading-core 拒单、拒绝手动平仓，并禁止 TP/SL / 强平触发 |
| `NO_QUOTE` | 同一 `symbol` 的 `bid / ask` 超过 `falconx.market.quote-quality.unchanged-max-age` 未变化，默认 `1m` | 只发布 `falconx.market.price.tick` 快照，不刷新可成交 Redis 最新价、ClickHouse、WebSocket 或 K 线 |
| `MARKET_CLOSED` | 交易日历、周内交易时段、节假日或人工例外判定该 `symbol` 休盘 | 不处理该 `symbol` 报价，不写 Redis / ClickHouse / Kafka / WebSocket / K 线 |
| `ABNORMAL` | `bid <= 0`、`ask <= 0` 或 `bid >= ask` | 只发布 `falconx.market.price.tick` 快照，不刷新可成交 Redis 最新价、ClickHouse、WebSocket 或 K 线 |

`falconx.market.price.tick` payload 必须携带 `quoteStatus / qualityReason`。`FRESH` 是唯一可成交状态；`STALE / NO_QUOTE / MARKET_CLOSED / ABNORMAL` 都不得进入下单、手动平仓、TP/SL 或强平触发路径。trading-core-service 仍必须按自身 `falconx.trading.stale.max-age` 和交易时间快照做二次校验，不能只信任 market-service 写入时的布尔值。

## 7. K 线边界

LP 文档提供 `POST /safe/external/kline/getKLine/page` K 线 API。

当前实现边界：

- 实时交易链路仍以 `price-compression` tick 推进。
- 平台实时 K 线仍由 `market-service` 内部聚合，OHLC 统一使用同一条标准报价中的 `mid` 价格；交易终端 K 线图只使用 REST 历史 K 线与 WebSocket `kline.{interval}` 推送绘制，不使用 `price.tick` 在前端合成或覆盖 K 线。
- LP K 线 API 只作为后续历史补数或外部对账专项入口；未在本轮替换实时 K 线生成。

若后续要求以 LP K 线覆盖平台内部聚合，必须单独冻结 K 线 owner、补数策略、冲突处理与测试证据。

## 8. 线程与日志

Socket.IO 回调不得直接执行完整业务链路。

当前 provider 使用 `market-lp-dispatch-*` 单线程执行器进入应用层，保证：

- 同一连接上的 tick 尽量保持顺序。
- 应用线程恢复 Spring Boot 应用 ClassLoader。
- 每条外部 tick 生成系统 traceId 并写入 Kafka header。

关键日志：

- `market.lp.provider.connecting`
- `market.lp.provider.connected`
- `market.lp.provider.subscribed`
- `market.lp.provider.price-compression.parsed`
- `market.lp.provider.dispatch.failed`
- `market.lp.provider.reconnect.scheduled`

## 9. 验证要求

最低本地验证：

```bash
mvn -pl falconx-market-service -am test
mvn -pl falconx-trading-core-service -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TradingKafkaMarketEventIntegrationTests,TradingQuoteSnapshotStaleIntegrationTests test
```

LP 真源验收必须额外提供：

- `market.lp.provider.connected`
- `market.lp.provider.subscribed`
- `market.lp.provider.price-compression.parsed quotes>0 accepted>0`
- Redis 最新价存在 `source=TM_QUOTE`
- ClickHouse `quote_tick.source=TM_QUOTE`
- Kafka `falconx.market.price.tick` payload `source=TM_QUOTE`

当前生产化准备证据（2026-04-29）：

- 按 `socket-client-demo` 修正 Socket.IO 握手后，本地 demo 使用其示例地址与 `APP-ID` 可在连接后收到 `price-compression decoded`。
- 用户确认当前 app 已开通实时行情推送权限，订阅 payload 不需要 wrapper，实时事件名为 `price-compression`。
- 按 LP 服务端代码核对后，`external-sub-symbol` 已修正为发送 JSON 字符串，避免 `netty-socketio` 的 `String.class` 监听器无法进入第三方订阅处理链路。
- `market-service` 使用 `socket-client-demo` 的示例地址与 `APP-ID` 覆盖启动后，已取得 `market.lp.provider.connected`、`market.lp.provider.subscribed` 与多条 `market.lp.provider.price-compression.parsed quotes=1 accepted=1 filtered=0`。
- 按 LP PDF 改为 app token 加密握手后，当前 `.env` 参数可通过 `wss://{domain}/safe/socket.io/` 完成真实连接；显式 `serverId=5 / 20` 订阅无推送，不传 `serverId` 时 LP 映射到 `17` 并可收到报价。
- `market-service` 使用当前 `.env` 参数与显式 `serverId=17` 启动后，已在订阅后 1 秒内取得 `market.quote.received source=TM_QUOTE`、`market.redis.written`、ClickHouse `market.analytics.quote.flush.completed` 与 Kafka `market.event.publish.completed topic=falconx.market.price.tick` 证据。

当前系统仍不得因为完成 LP 接入而写成“生产可用”。

历史说明：

- 运行时 Flyway migration 中已经存在的历史 symbol 储备脚本不会在本轮删除或重命名，避免已应用环境出现 Flyway 缺失迁移问题。
- 历史脚本只保留数据库演进事实，不代表当前存在旧外部行情源接入、订阅或报价解析能力。
