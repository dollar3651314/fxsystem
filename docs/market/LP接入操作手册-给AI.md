# LP 接入操作手册（给另一个 AI 用）

> 本文档面向"不熟悉 FalconX、需要从零完成 LP 自建行情源接入"的 AI/开发者。
> 不替代 [`LP自建行情源接入契约.md`](./LP自建行情源接入契约.md)，是其落地操作版。
>
> **目标 LP 提供商**：自建 LP（lifebyte / vibe.zerogridtech.com 系列）
> **协议**：Socket.IO v4 over WebSocket
> **传输**：二进制 + Snappy 压缩
> **最后验证日期**：2026-04-29（生产化准备证据节点）

---

## §1. 一句话现状

FalconX 一期**唯一生产行情源 = 自建 LP**，通过 Socket.IO v4 `wss://{domain}/safe/socket.io/` 接入，订阅事件 `external-sub-symbol`，接收事件 `price-compression`（Snappy 压缩 JSON），最终标准化为 `StandardQuote` 并写 Redis / Kafka / ClickHouse / WebSocket。

```
LP Socket.IO 服务端
   │
   │  wss://{domain}/safe/socket.io/?APP-ID=...&signature=...&encrypt=...
   ▼
SocketIoLpMarketQuoteProvider（io.socket:socket.io-client v4）
   │  emit "external-sub-symbol" {serverId:17, symbolList:[...]}
   │  on   "price-compression"  binary[0x04 + snappy(json)]
   ▼
LpWebSocketProtocolSupport
   │  解压 + 字段宽容映射 → ExternalRawQuote{ticker, bid, ask, ts, source="TM_QUOTE"}
   ▼
market-lp-dispatch-1 单线程 Executor（按 tick 顺序投递）
   ▼
MarketDataIngestionApplicationService.ingest
   │  → 源 symbol → 一/多平台 symbol（含 multiplier+adjustment）
   │  → 标准化（StandardQuote）+ 质量判定（FRESH/STALE/NO_QUOTE/MARKET_CLOSED/ABNORMAL）
   ▼
Redis 最新价（10s TTL）
ClickHouse quote_tick（10s 批 200 条）
Kafka falconx.market.price.tick
WebSocket /ws/v1/market 北向推送
K 线聚合 + falconx.market.kline.update
```

---

## §2. 必读源码（按重要性排序）

| # | 文件 | 作用 |
|---|---|---|
| 1 | `falconx-market-service/src/main/java/com/falconx/market/provider/SocketIoLpMarketQuoteProvider.java` | LP 连接 + 订阅 + 回调 + 重连 |
| 2 | `falconx-market-service/src/main/java/com/falconx/market/provider/LpWebSocketProtocolSupport.java` | URI 构造 + AES 加密 + SHA1 签名 + Snappy 解压 + JSON 解析 |
| 3 | `falconx-market-service/src/main/java/com/falconx/market/provider/LpCryptoSupport.java` | AES/SHA1 工具（独立可测，可单元测试加解密） |
| 4 | `falconx-market-service/src/main/java/com/falconx/market/provider/ExternalRawQuote.java` | provider → 应用层契约 record |
| 5 | `falconx-market-service/src/main/java/com/falconx/market/provider/MarketQuoteProvider.java` | provider 接口（接入新源时实现该接口）|
| 6 | `falconx-market-service/src/main/java/com/falconx/market/application/MarketFeedBootstrapRunner.java` | 启动器：服务启动后调 `provider.start(symbols, consumer)` + 定时刷 symbol 白名单 |
| 7 | `falconx-market-service/src/main/java/com/falconx/market/application/MarketDataIngestionApplicationService.java` | 接入主链路（标准化 → Redis/CH/Kafka/WS）|
| 8 | `falconx-market-service/src/main/java/com/falconx/market/config/MarketServiceProperties.java` | 所有 LP 配置项 |
| 9 | `falconx-market-service/src/main/resources/application.yml` | 默认配置 |
| 10 | `falconx-market-service/src/test/java/com/falconx/market/provider/LpWebSocketProtocolSupportTests.java` | URI/加密/签名/解压单元测试样本 |

---

## §3. Maven 依赖

`falconx-market-service/pom.xml`：

```xml
<dependency>
    <groupId>io.socket</groupId>
    <artifactId>socket.io-client</artifactId>
    <!-- 版本由 falconx-parent dependencyManagement 管理 -->
</dependency>
<dependency>
    <groupId>org.xerial.snappy</groupId>
    <artifactId>snappy-java</artifactId>
</dependency>
```

JSON 用 `tools.jackson.databind.ObjectMapper`（Jackson 3，全项目统一）。

---

## §4. 配置项

`falconx-market-service/src/main/resources/application.yml` §`falconx.market.lp.*`：

| 配置项 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `enabled` | - | `true` | 总开关；false 时 provider 启动跳过连接 |
| `domain` | `FALCONX_MARKET_LP_DOMAIN` 或 `.env` 内 `domain=` | 必填 | LP 域名，如 `vibe.zerogridtech.com`；自动加 `wss://` 前缀 |
| `socket-path` | - | `/safe/socket.io/` | Engine.IO path，**保持默认** |
| `app-id` | `FALCONX_MARKET_LP_APP_ID` 或 `.env` 内 `APP-ID=` | 必填 | LP 分配的 APP-ID |
| `token` | `FALCONX_MARKET_LP_TOKEN` 或 `.env` 内 `token=` | 必填 | LP app token，参与签名 + 加密 |
| `secret-key` | `FALCONX_MARKET_LP_SECRET_KEY` 或 `.env` 内 `secretKey=` | 必填 | **43 字符**的 AES key 原文（解码后是 32 字节 AES-256 key）|
| `server-id` | `FALCONX_MARKET_LP_SERVER_ID` | `17` | Socket.IO 实时订阅 serverId；当前账号实测**只有 17 推送**；显式 `5 / 20` 连接成立但无 tick |
| `meta-trade-version` | `FALCONX_MARKET_LP_META_TRADE_VERSION` 或 `.env` 内 `metaTradeVersion=` | `4` | K 线 API 才用（实时不用），MT4=4 / MT5=5 |
| `meta-trader-id` | `FALCONX_MARKET_LP_META_TRADER_ID` 或 `.env` 内 `metaTraderId=` | 同 `server-id` | K 线 API 才用 |
| `connect-timeout` | - | `10s` | Socket.IO 连接超时 |
| `reconnect-interval` | - | `5s` | 断线重连退避（固定间隔，未做指数）|
| `symbol-whitelist-refresh-cron` | - | `0 */1 * * * *` | 每分钟刷新 t_symbol 订阅白名单 |
| `symbol-whitelist-refresh-zone` | - | `UTC` | 上面 cron 的时区 |

**dev 启动**：把 LP 凭据写进项目根目录 `.env`（已 gitignore）：

```bash
# .env（项目根目录）
domain=vibe.zerogridtech.com
APP-ID=xxxxxxxx
token=xxxxxxxxxx
secretKey=43_chars_aes_key_base64_no_padding
serverId=17
metaTradeVersion=5
metaTraderId=17
```

`spring.config.import: optional:file:.env[.properties]` 自动加载，运行时映射到 `${domain:}` / `${APP-ID:}` 等占位符。

---

## §5. TLS / TrustStore

LP 服务端证书由 lifebyte 根 CA 签发（**非公网信任链**），JDK 默认 trust store 不认。

- 仓库已预置：`tools/lp-truststore.p12`
  - 格式：PKCS12
  - 密码：`falconx-lp`
  - alias：`lifebyte-root-ca`
  - SHA-256：`9F:AB:0F:AB:95:B4:02:94:01:D6:F7:B0:61:87:2C:6E:4D:62:49:30:1C:66:37:C4:40:DB:45:68:9E:5D:11:E3`

启动 market-service 必须传：

```bash
-Djavax.net.ssl.trustStore=$(pwd)/tools/lp-truststore.p12
-Djavax.net.ssl.trustStoreType=PKCS12
-Djavax.net.ssl.trustStorePassword=falconx-lp
```

**证书更新**（若 LP 切证书）：

```bash
openssl s_client -connect vibe.zerogridtech.com:443 -servername vibe.zerogridtech.com -showcerts </dev/null \
  | awk '/-----BEGIN/{c++; flag=1} flag{print > "/tmp/lp-cert-" c ".pem"} /-----END/{flag=0}'
# 根 CA 在 /tmp/lp-cert-2.pem
keytool -importcert -alias lifebyte-root-ca -file /tmp/lp-cert-2.pem \
  -keystore tools/lp-truststore.p12 -storepass falconx-lp -storetype PKCS12 -noprompt
```

> 当前 `tools/lp-truststore.p12` 是仓库内可直接使用的临时证据；**生产部署必须**走外部密钥治理 + 正式 trust store，不能长期依赖该文件（见 [`docs/setup/当前开发计划.md`](../setup/当前开发计划.md) §"自建 LP 唯一生产报价源"）。

---

## §6. 握手协议

### 6.1 URL 模板

```
wss://{domain}/safe/socket.io/?APP-ID={appId}&signature={signature}&encrypt={encrypt}&EIO=4&socketSource=1&socketVersion=1.1&nonce={nonce}&timestamp={timestamp}
```

固定参数：
- `EIO=4`
- `socketSource=1`
- `socketVersion=1.1`

### 6.2 `encrypt` 字段（AES-256-CBC + PKCS5）

明文结构（按字节顺序）：

```
[random16 (16 bytes)] [msgLen (4 bytes, big-endian)] [msg = token (UTF-8)] [appId (UTF-8)]
```

加密：
- 算法：`AES/CBC/PKCS5Padding`
- key：`Base64.decode(secretKey + "=")`（43 字符 → 加 `=` 凑 44 → Base64 解码 → 32 字节 AES-256 key）
- IV：取 key 的前 16 字节
- 输出：`Base64.encode(ciphertext)`

参考实现：`LpCryptoSupport.encrypt(appId, secretKey, message)` 或 `LpWebSocketProtocolSupport.encrypt(token, appId, secretKey, randomBytes)`。

### 6.3 `signature` 字段（SHA-1）

```
signature = SHA1(sort([token, timestamp, nonce, encrypt]).join(""))
```

- 4 个值按字典序升序排
- 拼接后 SHA-1
- 输出小写 hex（40 字符）

参考实现：`LpCryptoSupport.sign` / `LpWebSocketProtocolSupport.sha1Sorted`。

### 6.4 其他字段

- `nonce`：4 字节 hex 截前 8 字符，每次握手新生成
- `timestamp`：`Instant.now().toEpochMilli()` 毫秒时间戳

### 6.5 io.socket.client 配置

```java
IO.Options options = new IO.Options();
options.transports = new String[]{"websocket"};  // 不允许 polling
options.upgrade = false;                          // 直接 ws，不走升级
options.reconnection = false;                     // 关闭默认重连（自己管理）
options.timeout = 10_000;
options.path = "/safe/socket.io/";                // Engine.IO path
options.query = "APP-ID=...&signature=...&encrypt=...&..."; // §6.1 query string

Socket socket = IO.socket(URI.create("wss://{domain}/"), options);
```

注意：`IO.socket(uri, options)` 的 `uri` **只保留 `scheme://host:port/`**，query 必须通过 `IO.Options.query` 传入，否则签名 query 会被 io.socket 截掉。

---

## §7. 订阅

连接成功 (`Socket.EVENT_CONNECT`) 后 emit：

```java
String payload = """
    {"serverId":17,"symbolList":["XAUUSD","BTCUSD","EURUSD"]}
    """;
socket.emit("external-sub-symbol", payload);  // 必须以 JSON 字符串发送！
```

**关键**：
- event 名：`external-sub-symbol`
- payload：**JSON 字符串**（不能转成 Socket.IO Map/Object，否则服务端 netty-socketio `String.class` 监听器收不到）
- `serverId` 必须 `17`（当前账号实时推送 serverId）
- `symbolList`：从 market owner `t_symbol_quote_mapping.enabled=1 AND lp_subscribe_enabled=1 AND t_symbol.status=1` 去重的 `source_symbol`
- 保留原始 LP MT5 代码（包括 `.NAS` / `.risk` 等后缀），不能规整

---

## §8. 报价回调

监听 event：`price-compression`

回调入参是二进制：

```java
socket.on("price-compression", args -> {
    Object payload = args[0];      // byte[] 或 ByteBuffer
    byte[] frame = toBytes(payload);
    int offset = (frame.length > 0 && frame[0] == 0x04) ? 1 : 0;  // 移除 socket.io binary header
    String json = Snappy.uncompressString(frame, offset, frame.length - offset, UTF_8);
    // 解析 JSON 数组 → [{symbol, bid, ask, timestamp}, ...]
});
```

### 8.1 JSON 字段宽容映射

LP 实测多种字段命名，提取时按下面顺序找第一个非空值：

| 语义 | 备选字段（按优先级）|
|---|---|
| symbol | `symbol / ticker / instrument / Symbol / Ticker` |
| bid | `bid / bidPrice / Bid / BidPrice` |
| ask | `ask / askPrice / Ask / AskPrice` |
| timestamp | `receivingTime / datetimeUtc / datetime / ts / timestamp / time / quoteTimestamp / ctm / ctmUtc / Time` |

JSON 可能嵌套在 `data / prices / priceList / quotes` 任一层下，递归 collect。

### 8.2 时间字段处理

- 数字（10 位）→ epoch seconds
- 数字（13+ 位）→ epoch millis
- 字符串 → `OffsetDateTime.parse`

### 8.3 时间戳替换（关键）

**进入接入主链路前**，把 `ExternalRawQuote.ts` 替换为 market-service 当前 UTC 时间：

```java
ExternalRawQuote withPlatformTimestamp = new ExternalRawQuote(
    quote.ticker(), quote.bid(), quote.ask(),
    OffsetDateTime.now(ZoneOffset.UTC),     // ← 用平台时间，不用 LP 时间
    quote.source()
);
```

理由：LP 源时间可能稳定漂移（账号订阅大量 symbol 时单线程排队会让源时间比平台时间晚几秒），用 LP 时间会被下游 `STALE` 守卫误判。LP 源时间只用于解析校验，**不作为业务轴**。

### 8.4 线程模型

Socket.IO 回调线程 **不允许**直接执行 Redis/Kafka/DB/ClickHouse 主链路。必须投递到独立 Executor：

```java
ExecutorService quoteDispatchExecutor = Executors.newSingleThreadExecutor(
    Thread.ofPlatform().name("market-lp-dispatch-", 0).daemon(true).factory()
);
// 同时记得在 dispatch 时恢复 Spring Boot Application ClassLoader 以及生成新 traceId
```

---

## §9. 报价数据契约

### 9.1 provider 层

```java
public record ExternalRawQuote(
    String ticker,         // LP 源 symbol（MT5 原始代码，含后缀）
    BigDecimal bid,
    BigDecimal ask,
    OffsetDateTime ts,     // 已替换为平台 UTC 时间
    String source          // 固定 "TM_QUOTE"
) {}
```

### 9.2 标准化后（StandardQuote → Kafka）

`falconx.market.price.tick` event payload（[`MarketPriceTickEventPayload.java`](../../falconx-market-contract/src/main/java/com/falconx/market/contract/event/MarketPriceTickEventPayload.java)）：

```java
{
  "symbol": "XAUUSD",            // 平台 symbol（mapping 后）
  "bid": "4657.38",
  "ask": "4657.62",
  "mid": "4657.50",              // (bid + ask) / 2
  "mark": "4657.50",             // 当前 = mid，预留
  "ts": "2026-05-13T12:00:00.123Z",
  "source": "TM_QUOTE",
  "stale": false,
  "quoteStatus": "FRESH",        // FRESH/STALE/NO_QUOTE/MARKET_CLOSED/ABNORMAL
  "qualityReason": null
}
```

---

## §10. Symbol 映射

`t_symbol_quote_mapping` 把 LP 源 symbol 映射成一/多个平台 symbol：

```
platformBid = sourceBid * priceMultiplier + bidAdjustment
platformAsk = sourceAsk * priceMultiplier + askAdjustment
```

例：
- `XAU100` → `source_symbol=XAUUSD, priceMultiplier=100`（金价放大 100 倍）
- `AAAUSD` → `source_symbol=XAUUSD, bidAdjustment=0.5, askAdjustment=0.5`（叠加固定加点）

provider 订阅集合 = `SELECT DISTINCT source_symbol FROM t_symbol_quote_mapping WHERE enabled=1 AND lp_subscribe_enabled=1 AND source_symbol IN (SELECT symbol FROM t_symbol WHERE status=1)`。

---

## §11. 行情质量状态机

| 状态 | 触发条件 | 后续行为 |
|---|---|---|
| `FRESH` | 通过时间 + 价格 + 交易时段全部检查 | 全链路写入：Redis + ClickHouse + Kafka + WS + K 线 |
| `STALE` | `abs(now - quote.ts) > 5s` (`falconx.market.stale.max-age`) | 仅 Kafka snapshot；不刷 Redis/CH/WS/K 线；trading-core 拒所有交易动作 |
| `NO_QUOTE` | bid/ask 超过 `1m` 未变化 | 同 STALE |
| `MARKET_CLOSED` | 交易日历 / 周内交易时段判定休盘 | 完全跳过该 tick |
| `ABNORMAL` | bid≤0 / ask≤0 / bid≥ask | 同 STALE |

`FRESH` 是唯一可成交状态。trading-core-service 必须按自身 `falconx.trading.stale.max-age` 做二次校验。

---

## §12. 启动与验证

### 12.1 启动命令（dev）

```bash
cd /Users/ives/WorkCode/Claude/FalconX
java -Djavax.net.ssl.trustStore=$(pwd)/tools/lp-truststore.p12 \
     -Djavax.net.ssl.trustStoreType=PKCS12 \
     -Djavax.net.ssl.trustStorePassword=falconx-lp \
     -jar falconx-market-service/target/falconx-market-service-1.0.0-SNAPSHOT.jar
```

`.env` 已通过 `spring.config.import` 自动加载，无需 `-D` 单独传 LP 凭据。

### 12.2 验证日志（按顺序）

```
market.feed.bootstrap.start
market.feed.bootstrap.symbols resolvedCount=N
market.lp.provider.connecting domain=... symbolCount=N
market.lp.provider.connected domain=...
market.lp.provider.subscribed serverId=17 symbolCount=N
market.lp.provider.price-compression.parsed quotes=K accepted=K filtered=0
market.quote.received symbol=XAUUSD source=TM_QUOTE quoteTs=... stale=false quoteStatus=FRESH
market.redis.written symbol=XAUUSD
market.event.publish.completed topic=falconx.market.price.tick symbol=XAUUSD
```

### 12.3 验收清单（4 个真源证据缺一不可）

```bash
# 1. Redis 最新价
redis-cli -p 6380 GET 'falconx:market:quote:XAUUSD'
# → 应含 source=TM_QUOTE

# 2. ClickHouse quote_tick
clickhouse-client --port 9000 --query "SELECT symbol, source, count() FROM falconx_market_analytics.quote_tick WHERE event_time > now() - INTERVAL 1 MINUTE GROUP BY symbol, source ORDER BY 3 DESC LIMIT 5"
# → 应含 TM_QUOTE 来源

# 3. Kafka price.tick
kafka-console-consumer --bootstrap-server localhost:9092 --topic falconx.market.price.tick --from-beginning --max-messages 3
# → 应见 source: "TM_QUOTE"

# 4. WebSocket 北向
wscat -c 'ws://localhost:18080/ws/v1/market?token=...'
> {"type":"subscribe","symbols":["XAUUSD"]}
# → 应见 tick 推送
```

### 12.4 单元测试

```bash
mvn -pl falconx-market-service -am test
# 重点 LP 测试：
# - LpWebSocketProtocolSupportTests（URI/加密/签名/解压）
# - LpCryptoSupportTests（AES/SHA1 工具）
# - SocketIoLpMarketQuoteProviderTests（dispatch 行为）
# - MarketSingleQuoteSourceArchitectureTests（架构约束：唯一源 = TM_QUOTE）
# - MarketPriceTickMainlineIntegrationTests（接入主链路集成）
```

---

## §13. 已知坑（实操踩过的）

1. **`external-sub-symbol` 必须发字符串**：LP 服务端 `netty-socketio` 注册的是 `String.class` 监听器，发 `Map<String, Object>` 会被解析成 Socket.IO JSON object 路由失败。
2. **`serverId=17` 才推数据**：当前账号实测 `serverId=5 / 20` 连接成立 + 订阅事件成立但 10 秒内无 `price-compression`。LP 文档历史 K 线 API 用的 `serverId=20` 不能映射到 Socket.IO `serverId`。
3. **`server-id=0` 跳过**：不传 `serverId` 时 LP 会按 APP-ID 推断到 17，但**不要**依赖这个隐式行为，运行配置必须显式传 17。
4. **Socket.IO URI 不要把 query 拼到 URI 里**：`IO.socket(URI("wss://host/?APP-ID=..."))` 会丢失 query。必须 `IO.socket(URI("wss://host/"), options{path, query})`。
5. **二进制帧首字节 0x04**：Socket.IO 把 binary 消息加一个 packet type byte，Snappy 解压前必须 strip。
6. **AES key 解码加 `=`**：43 字符 secretKey 不是标准 Base64 padding，要补 `=` 凑 44 字符再 `Base64.decode`。
7. **时间戳用平台时间，不要用 LP 时间**：见 §8.3。
8. **回调线程不要做业务**：Socket.IO 回调阻塞会拖累所有后续 tick，必须投递到独立单线程 Executor 保序。
9. **symbol 不能 normalize 大小写或剥后缀**：`AAPL.NAS` 必须原样发，否则 LP 找不到。
10. **TrustStore 不传服务起不来**：JDK 不认 lifebyte 根 CA，连接立刻报 `PKIX path building failed`。

---

## §14. 改 owner / 配 新 LP 的检查清单

如果要接入第二个 LP 或换 LP：

- [ ] 不要在 `SocketIoLpMarketQuoteProvider` 内分支化 LP 类型 — 新写一个实现 `MarketQuoteProvider` 接口的 Provider，通过 Spring `@Profile` 或自定义条件切换
- [ ] `ExternalRawQuote.source` 必须新建一个枚举值（如 `TM_QUOTE_V2`），与 `TM_QUOTE` 不混用
- [ ] `t_symbol_quote_mapping.source_symbol` 命名空间冲突时新加 `source_lp` 列区分
- [ ] 更新 `MarketSingleQuoteSourceArchitectureTests`（当前断言唯一源是 `TM_QUOTE`，会失败）
- [ ] `falconx.market.lp.*` 重命名为 `falconx.market.lp.tm-quote.*`，新 LP 配 `falconx.market.lp.<name>.*`
- [ ] 同时跑两个 LP 时，按 symbol 拆 source 优先级，或按 mapping 显式选源
- [ ] 重新生成 trustStore（如证书链不同）

---

## §15. 相关文档

| 文档 | 用途 |
|---|---|
| [`LP自建行情源接入契约.md`](./LP自建行情源接入契约.md) | 契约层规范（本文档的"宪法"）|
| [`docs/api/WebSocket接口规范.md`](../api/WebSocket接口规范.md) | 北向 `/ws/v1/market` 推送格式 |
| [`docs/event/Kafka事件规范.md`](../event/Kafka事件规范.md) | `falconx.market.price.tick / kline.update` payload 契约 |
| [`docs/database/falconx一期数据库设计.md`](../database/falconx一期数据库设计.md) §符号管理 | `t_symbol / t_symbol_quote_mapping` schema |
| [`docs/setup/开发启动手册.md`](../setup/开发启动手册.md) | TrustStore 生成 + 服务启动命令 |
| [`docs/process/BBook一期完成开发计划.md`](../process/BBook一期完成开发计划.md) §"自建 LP 唯一生产报价源" | 当前 LP 接入的范围口径 |

---

## §16. 一句话开发流程

> 拿到 LP `domain / APP-ID / token / secretKey` 4 元组 → 写入 `.env` → 把 `tools/lp-truststore.p12` 通过 `-Djavax.net.ssl.trustStore` 传给 JVM → 启动 `market-service` → 看到 `market.lp.provider.subscribed` + `market.quote.received source=TM_QUOTE` 日志 = 接入完成。
