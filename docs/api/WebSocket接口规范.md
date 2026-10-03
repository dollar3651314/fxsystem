# FalconX v1 WebSocket 接口规范

## 1. 目标

统一 FalconX v1 实时行情推送与用户交易实时推送的 WebSocket 协议、消息格式、认证方式和连接管理规则。

适用服务：

- `falconx-gateway`（接入层）
- `falconx-market-service`（推送数据源）
- `falconx-trading-core-service`（用户交易实时推送数据源）

## 2. 连接端点

外部客户端统一通过 gateway 连接：

```
ws://{host}/ws/v1/market
ws://{host}/ws/v1/trading
```

gateway 将 `/ws/v1/market` 连接代理到 `market-service`，将 `/ws/v1/trading` 连接代理到 `trading-core-service`。

说明：

- `/ws/v1/market` 只推送公共行情数据，不允许混发用户私有数据
- `/ws/v1/trading` 只推送当前登录用户自己的账户、订单、成交、持仓、保证金、账本和强平变更
- 两个端点必须独立订阅，客户端不得通过行情端点接收用户交易私有数据

## 3. 认证方式

WebSocket 握手时通过 Query Parameter 传入 Access Token：

```
ws://{host}/ws/v1/market?token=<accessToken>
ws://{host}/ws/v1/trading?token=<accessToken>
```

规则：

- `token` 缺失或无效：握手阶段返回 `HTTP 401`，拒绝升级连接
- `token` 过期或在黑名单：同上处理
- `token` 对应用户状态为 `BANNED`：握手阶段返回 `HTTP 403`
- token 验证逻辑与 REST 接口一致（gateway 层统一校验）
- 连接建立后不再重复验证 token（直到连接断开重连）
- gateway 会在握手响应头返回 `X-Trace-Id`，并在向下游服务代理时透传内部 `X-User-*` 与 `X-Trace-Id`

## 4. 行情订阅协议

### 4.1 订阅请求

客户端发送 JSON 文本帧：

```json
{
  "type": "subscribe",
  "requestId": "req-001",
  "channels": ["price.tick", "kline.1m"],
  "symbols": ["EURUSD", "BTCUSD"]
}
```

字段说明：

- `type`：固定为 `subscribe`
- `requestId`：客户端自定义请求 ID，用于关联响应
- `channels`：订阅的数据类型，支持 `price.tick` 和 `kline.{interval}`
- `symbols`：订阅的品种列表，传空数组表示订阅当前用户组可见的全部可用品种（一期不推荐，按需订阅）；symbol 使用平台展示和交易 symbol。默认 LP 快照 symbol 保留 `.` 与普通市场后缀，例如 `AAPL.NAS`；自定义 symbol 如 `AAAUSD / XAU100` 由 market-service 映射到 LP 源。后端订阅上游 LP 时使用 `source_lp_code + source_symbol` 选定源，再映射回 `platform_symbol` 推送；WebSocket 订阅不按 `.p / .c / .f` 或其他后缀做特殊限制，是否可订阅由 mapping 启停、LP 订阅开关、源 symbol 状态和用户组可见性决定

`kline.{interval}` 支持的周期：

- `kline.1m`、`kline.5m`、`kline.15m`、`kline.1h`、`kline.4h`、`kline.1d`

### 4.2 订阅响应

服务端返回确认帧：

```json
{
  "type": "subscribed",
  "requestId": "req-001",
  "channels": ["price.tick", "kline.1m"],
  "symbols": ["EURUSD", "BTCUSD"]
}
```

若订阅包含 `price.tick`，且 market-service 当前存在该 symbol 的 Redis 最新价或最后有效参考价，服务端会在确认帧后立即补发一帧当前报价快照，消息类型仍为 `price.tick`。`quoteStatus=FRESH` 表示可成交实时价；`STALE / NO_QUOTE / MARKET_CLOSED / ABNORMAL` 只能作为展示参考。若没有任何可用价格，服务端只返回订阅确认，不补发报价快照。

若订阅请求有误（如 symbol 不存在或不属于当前用户组可见范围）：

```json
{
  "type": "error",
  "requestId": "req-001",
  "code": "30001",
  "message": "symbol not found: INVALID"
}
```

### 4.3 取消订阅

```json
{
  "type": "unsubscribe",
  "requestId": "req-002",
  "channels": ["kline.1m"],
  "symbols": ["EURUSD"]
}
```

服务端返回：

```json
{
  "type": "unsubscribed",
  "requestId": "req-002",
  "channels": ["kline.1m"],
  "symbols": ["EURUSD"]
}
```

## 5. 用户交易实时订阅协议

### 5.1 初始快照

客户端连接 `/ws/v1/trading` 成功后，`trading-core-service` 会立即推送一次当前账户快照：

```json
{
  "type": "account.snapshot",
  "channel": "account",
  "requestId": null,
  "data": {
    "accountId": 1001,
    "userId": 30001,
    "currency": "USDT",
    "balance": 2000.00000000,
    "frozen": 0.00000000,
    "marginUsed": 1000.00000000,
    "available": 1000.00000000,
    "marginMode": "ISOLATED",
    "equity": 2000.00000000,
    "marginLevel": null,
    "marginLevelStatus": "HEALTHY",
    "openPositions": []
  },
  "ts": "2026-04-30T12:00:00Z"
}
```

> `marginMode`（V11 起）/ `equity` / `marginLevel` / `marginLevelStatus`（STAGE-14E1 起）字段含义与推送语义见 [§5.4](#54-stage-14e1-双币--marginlevel-字段最终切换硬-break无-legacy-兼容)。`openPositions[]` 已在 STAGE-14E1 硬切双币（删 `unrealizedPnl`，加 `quoteCurrency / fxRate / unrealizedPnlInQuote / unrealizedPnlInAccount / isolatedMargin`）。

### 5.2 订阅请求

```json
{
  "type": "subscribe",
  "requestId": "user-sub-001",
  "channels": ["account", "orders", "positions", "trades", "margin", "ledger", "liquidations"]
}
```

支持的用户私有频道：

- `account`：账户余额、冻结、保证金占用和 OPEN 持仓快照
- `orders`：订单状态变化
- `positions`：持仓状态、TP/SL 和强平价变化
- `trades`：成交记录
- `margin`：逐仓保证金追加结果
- `ledger`：最新账本流水提示；客户端需要完整账本时必须用 REST 分页补偿查询
- `liquidations`：强平结果

服务端确认：

```json
{
  "type": "subscribed",
  "channel": null,
  "requestId": "user-sub-001",
  "data": {
    "channels": ["account", "orders", "positions", "trades", "margin", "ledger", "liquidations"]
  },
  "ts": "2026-04-30T12:00:01Z"
}
```

### 5.3 用户交易推送信封

`/ws/v1/trading` 的所有业务推送统一使用下面信封：

```json
{
  "type": "order.update",
  "channel": "orders",
  "requestId": null,
  "data": {
    "orderId": 9001,
    "orderNo": "O202604300001",
    "symbol": "BTCUSD",
    "side": "BUY",
    "orderType": "MARKET",
    "quantity": 1.00000000,
    "filledPrice": 10000.00000000,
    "status": "FILLED",
    "rejectReason": null
  },
  "ts": "2026-04-30T12:00:02Z"
}
```

已冻结的首版推送类型：

| type | channel | data |
| --- | --- | --- |
| `account.snapshot` | `account` | `TradingAccountResponse` |
| `account.update` | `account` | `TradingAccountResponse` |
| `order.update` | `orders` | `TradingOrderItemResponse` |
| `trade.created` | `trades` | `TradingTradeItemResponse` |
| `position.update` | `positions` | `TradingPositionItemResponse` |
| `position.pnl` | `positions` | `TradingPositionPnlUpdatePayload`（轻量 PnL 增量帧，按 symbol 100ms 节流） |
| `margin.update` | `margin` | `{ position, account }` |
| `ledger.created` | `ledger` | 最新一条 `TradingLedgerItemResponse`；完整账本以 REST 为准 |
| `liquidation.update` | `liquidations` | `TradingLiquidationItemResponse` |
| `risk-controls.update` | `positions` | `TradingPositionItemResponse` |
| `error` | `null` | `{ code, message }` |
| `pong` | `null` | `{ ts }` |

当前首版不推送 `risk.warning`。BBook 自营风控闭环落地后，必须另行冻结风险提醒消息体。

### 5.4 STAGE-14E1 双币 / MarginLevel 字段最终切换（硬 break，无 legacy 兼容）

> **🔴 STAGE-14E1（2026-06-02 收口，master §7.5 WebSocket 最终 break）**：把 `position.update` / `position.pnl` 的浮盈亏从单币硬切为双币 + 元数据，并给 `account.update` / `account.snapshot` 加 `equity / marginLevel / marginLevelStatus`。**这是对外 break change，无 legacy 兼容**：`position.update` / `position.pnl` 直接删除单币字段 `unrealizedPnl`，不保留旧名并存。后端 `trading-core-service` 与客户端 `falconx-frontend` 在同一 STAGE-14E1 切片内同步切换，**部署必须 trading-core + 前端同窗口上线**，否则旧客户端解析新帧会丢失浮盈亏字段。

#### 5.4.1 `position.update` / `position.pnl` data 字段（双币切换后）

`position.update` data = `TradingPositionItemResponse`（完整持仓项，含 PnL 双币）；`position.pnl` data = `TradingPositionPnlUpdatePayload`（轻量 PnL 增量帧，复用 `CHANNEL_POSITIONS`，无需新增订阅 channel）。两者浮盈亏口径一致：

| 字段 | 类型 | 说明 | 变更 |
| --- | --- | --- | --- |
| ~~`unrealizedPnl`~~ | ~~BigDecimal~~ | ~~单币（QC 原币）浮盈亏~~ | **🔴 删除（无 legacy）** |
| `quoteCurrency` | String | 计价币代码（QC，来源 `SymbolSpec`）；过渡期 spec 未补齐时可能为 null | 新增 |
| `fxRate` | BigDecimal | fx(QC→AC) 换算率；同币种=1；实时 FX 不可用降级用开仓冻结 `entryFxRate`；QC 或 entryFxRate 均缺失时 null | 新增 |
| `unrealizedPnlInQuote` | BigDecimal | QC 原币浮盈亏（含 STAGE-12 markup，由实时 markPrice 即时算） | 新增 |
| `unrealizedPnlInAccount` | BigDecimal | AC 账户币浮盈亏 = `unrealizedPnlInQuote × fxRate`（含 FX 降级） | 新增 |
| `isolatedMargin` | BigDecimal | 逐仓占用保证金（= `position.margin`，AC 账户币）；ISOLATED 仓有值，CROSS 仓为 null（保证金不归属单仓，不新增 DB 列，用 margin 映射） | 新增 |

> 实时算口径：复用 `TradingUserRealtimePayloadFactory.calculatePositionPnlInAccount`（与 STAGE-14D2 同函数），FX 内存查 + 不可用降级 `entryFxRate`（不抛）。`position.update` 仍在 fill / close / TP-SL / margin 事件推送整持仓；`position.pnl` 按 symbol 100ms 节流推送轻量增量。

#### 5.4.2 `account.update` / `account.snapshot` data 字段（MarginLevel 加列）

data = `TradingAccountResponse`，在既有 `balance / frozen / marginUsed / available / marginMode / openPositions` 基础上新增：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `equity` | BigDecimal | 账户净值（AC 账户币），实时算 `Equity = balance + frozen + Σ uPnL_i(AC)`（口径复用 `AccountEquityCalculator`）；FX 不可用降级时为 null（不抛） |
| `marginLevel` | BigDecimal | 账户级保证金率（百分比，已 ×100；2 位 HALF_UP）；无持仓 / 除零 / FX 降级时为 null |
| `marginLevelStatus` | String | 保证金率阶段状态 `HEALTHY` / `MARGIN_CALL` / `STOP_OUT`，由 `MarginLevelMonitor` 基于 `marginLevel` + `t_risk_config` 阈值实时判定；marginLevel 为 null 时为 `HEALTHY` |

`openPositions[]`（= `TradingAccountPositionResponse`）同步硬切双币：删除单币 `unrealizedPnl`，加 `quoteCurrency / fxRate / unrealizedPnlInQuote / unrealizedPnlInAccount / isolatedMargin`，口径与 §5.4.1 一致。

> **marginLevel 推送语义（STAGE-14E1 据实）**：`equity / marginLevel / marginLevelStatus` 在 **fill / close 事件**随 `account.update` 推送（非 per-tick）。浮窗显示的是最近一次 fill/close 后的 MarginLevel 值；per-tick 实时刷新属后续 refinement。`account.snapshot`（连接建立 / 重连）携带当前快照值。

### 5.5 STAGE-14E2 管理端实时推送双币 / quoteCurrency 字段最终切换（硬 break，无 legacy 兼容）

> **🔴 STAGE-14E2（2026-06-02 收口，master §8.3 E「admin 同步无 legacy」）**：管理端实时监控推送（`/ws/v1/admin` 的 `admin.position.update` / `admin.exposure.update` / `admin.position.summary`）与 E1 客户端口径同步硬切双币，**无 legacy 兼容**：`admin.position.update` 删单币浮盈亏 `unrealizedPnl`，加双币 + 元数据；`admin.exposure.update` 补 `quoteCurrency`；`admin.position.summary` 平台浮盈亏汇总口径由报价币（QC）修正为账户币（AC，跨 symbol 求和才有意义）。双币算法与客户端共用同一实现 `TradingRealtimeDualPnlSupport`（trading-core 内 client + admin 单一来源，commit `4804f043`）。**这是对外 break：`trading-core-service` 与 `console-frontend` 必须同窗口部署**，否则旧管理端解析新帧会丢失浮盈亏字段。

#### 5.5.1 `admin.position.update` data 字段（`AdminPositionPnlUpdatePayload.Item`，双币切换后）

| 字段 | 类型 | 说明 | 变更 |
| --- | --- | --- | --- |
| `positionId` / `userId` | String | 雪花 ID（>2^53）用 String 输出防前端精度损失 | 不变 |
| `side` / `markPrice` | String / BigDecimal | 方向 / 实时标记价 | 不变 |
| ~~`unrealizedPnl`~~ | ~~BigDecimal~~ | ~~单币（QC 原币）浮盈亏~~ | **🔴 删除（无 legacy）** |
| `quoteCurrency` | String | 计价币代码（QC，来源 `SymbolSpec`）；过渡期 spec 未补齐时可能为 null | 新增 |
| `fxRate` | BigDecimal | fx(QC→AC) 换算率；同币种=1；实时 FX 不可用降级用开仓冻结 `entryFxRate`；缺失为 null | 新增 |
| `unrealizedPnlInQuote` | BigDecimal | QC 原币浮盈亏 | 新增 |
| `unrealizedPnlInAccount` | BigDecimal | AC 账户币浮盈亏 = `unrealizedPnlInQuote × fxRate`（含 FX 降级） | 新增 |

> 口径复用 `TradingRealtimeDualPnlSupport`（与客户端 §5.4.1 同一实现）。

#### 5.5.2 `admin.exposure.update` data 字段（`AdminExposureUpdatePayload`）

在既有 `symbol / totalLongQty / totalShortQty / netExposure / netExposureUsd / updatedAt` 基础上新增：

| 字段 | 类型 | 说明 | 变更 |
| --- | --- | --- | --- |
| `quoteCurrency` | String | 计价币代码（QC，来源 `SymbolSpec`），过渡期可能为 null；对齐 E1 双币口径，供管理端按报价币聚合敞口 | 新增 |

#### 5.5.3 `admin.position.summary` 平台浮盈亏汇总口径修正

- `totalUnrealizedPnl`（跨 symbol 平台级汇总）由**报价币（QC）**修正为**账户币（AC）**：仅 AC 口径跨不同 symbol/不同 QC 求和才有意义。`AdminPositionSummaryAggregator` 按 symbol 累计 `unrealizedPnlInAccount`，FX + entryFxRate 均不可用时该 symbol 不计入（用 null 移除条目避免陈旧值串入平台 sum）。

> **部署协调**：admin WS break 与 E1 客户端 break 同属硬切，无 legacy 并存。`trading-core-service`（推送方）与 `console-frontend`（消费方）必须同窗口部署。

## 6. 行情推送消息格式

### 6.1 价格 tick 推送

```json
{
  "type": "price.tick",
  "symbol": "EURUSD",
  "bid": "1.08326",
  "ask": "1.08336",
  "mid": "1.08331",
  "mark": "1.08331",
  "baseBid": "1.08321",
  "baseAsk": "1.08331",
  "baseMid": "1.08326",
  "hasMarkup": true,
  "ts": "2026-04-16T16:00:00.123Z",
  "source": "TM_QUOTE",
  "stale": false,
  "quoteStatus": "FRESH",
  "qualityReason": null
}
```

- `bid` / `ask` / `mid` / `mark` 为**已含用户组加点**的对外展示/成交语义价：market-service 按连接 session 的 `X-User-Group-Code` 在 `t_symbol_group_visibility` 可见性基础上叠加 `t_symbol_group_markup` 的 bid/ask 绝对加点（STAGE-12）。缺省组 `default` 加点为 0 时与基准价一致。
- `baseBid` / `baseAsk` / `baseMid` 为**未加点的基准报价**（STAGE-12 `f24faf65`/`90e59690` 补），`hasMarkup` 标记该帧是否实际叠加了组加点。客户端 K 线统一用基准 `baseMid` 聚合，避免组加点污染跨组共享的 K 线数据源（K 线本身仍只来自 REST 历史 + `kline.{interval}` 推送，`price.tick` 不作为 K 线数据源）。

### 6.2 K 线推送

```json
{
  "type": "kline.1m",
  "symbol": "EURUSD",
  "interval": "1m",
  "open": "1.08300",
  "high": "1.08350",
  "low": "1.08290",
  "close": "1.08326",
  "volume": "0",
  "openTime": "2026-04-16T16:00:00Z",
  "closeTime": "2026-04-16T16:00:59Z",
  "isFinal": false
}
```

### 6.3 stale 通知帧

当最新可成交报价超过 stale 判定阈值时，服务端可以向已订阅 `price.tick` 的连接推送轻量状态通知：

```json
{
  "type": "price.tick",
  "symbol": "EURUSD",
  "stale": true,
  "quoteStatus": "STALE",
  "qualityReason": "QUOTE_TIME_DRIFT_EXCEEDED",
  "ts": "2026-04-16T16:00:05.123Z"
}
```

该通知帧只用于让客户端把行情状态从 `LIVE` 降级为 `REFERENCE`。客户端不得把 stale 通知帧写入分时图历史、不得用它合成 K 线，也不得用它触发交易。

说明：

- `isFinal`：当前 K 线是否已收盘。`false` 表示当前 K 线仍在更新中，`true` 表示 K 线已完结
- 收盘时会额外推送一次 `isFinal: true` 的消息
- `kline.{interval}` 的 `open / high / low / close` 统一使用同周期内标准报价 `mid` 聚合生成，交易终端 K 线图只使用 REST 历史 K 线与 WebSocket `kline.{interval}` 推送绘制，不得用 `price.tick` 在前端合成或覆盖 K 线
- `price.tick` 只用于行情列表、Tick 图、下单面板、stale / quoteStatus 状态降级和实时交易状态展示，不作为 K 线数据源
- `mark` 当前仍是 `market-service` 的兼容字段；`trading-core-service` 做逐仓估值、TP/SL、强平和账户浮盈亏时，统一按方向从 `bid / ask` 解析有效标记价，不直接使用该单值字段
- `quoteStatus=FRESH` 是唯一可成交状态；`STALE / NO_QUOTE / MARKET_CLOSED / ABNORMAL` 只能作为展示或审计参考，交易侧不得用这些状态触发成交、TP/SL 或强平

### 6.4 错误推送

服务端主动推送的错误通知：

```json
{
  "type": "error",
  "code": "30002",
  "message": "price source disconnected, data may be stale"
}
```

## 7. 心跳机制

- `/ws/v1/market`：服务端每 `30s` 发送 WebSocket Ping 帧（协议层 ping，非应用层 JSON），客户端必须在 `10s` 内回复 Pong 帧；超时未回复时服务端主动关闭连接（`1001 Going Away`）
- `/ws/v1/trading`：首版支持应用层 `ping -> pong`，不主动发送协议层 Ping；客户端应每 `30s` 发送应用层 `ping` 做存活探测，断线后按重连策略重新订阅

客户端也可主动发送应用层心跳：

```json
{
  "type": "ping",
  "ts": "2026-04-16T16:00:00Z"
}
```

服务端回复：

```json
{
  "type": "pong",
  "ts": "2026-04-16T16:00:00Z"
}
```

## 8. 连接关闭码

| Code | 含义 |
|---|---|
| `1000` | 正常关闭 |
| `1001` | 服务端主动关闭（心跳超时、维护） |
| `1008` | 认证失败（token 失效或被踢下线） |
| `1011` | 服务端内部错误 |

## 9. 客户端重连策略

- 连接断开后，客户端应使用指数退避重连：`1s, 2s, 4s, 8s, 16s`，最大间隔 `30s`
- 重连成功后需重新发送订阅请求（服务端不保存断线前的订阅状态）；`/ws/v1/trading` 会在新连接建立后再次推送 `account.snapshot`
- 重连期间如收到 `1008`（认证失败），需先刷新 token 再重连
- 用户交易实时推送是 best-effort 通知，客户端必须在重连后用 REST 查询账户、订单、持仓、账本和强平记录补偿缺口

## 10. 并发连接限制

- 同一用户最多允许 `5` 个并发 WebSocket 连接（一期值，后续按需调整）
- 超过限制时，新连接握手阶段返回 `HTTP 429`
- 当前连接数限制是 gateway 单实例内存计数；多 gateway 实例下需要后续引入 Redis 维度全局限制

## 11. 实现要求

- gateway 层负责 token 验证和连接数限制
- gateway 层负责 `/ws/v1/market` 和 `/ws/v1/trading` 握手鉴权、`X-Trace-Id` 生成、并发连接限制和代理透传
- market-service 负责行情推送逻辑
- trading-core-service 负责用户交易实时推送逻辑
- 用户交易推送必须在订单、持仓、账户、账本和强平事实所属本地事务提交后发送；推送失败只记日志，不回滚已提交业务事实
- 用户私有推送不得接受客户端传入 `userId`，只能使用 gateway 透传的 `X-User-Id`
- 行情推送频率不超过原始 tick 频率，不人工限速（交由 LP 自建行情源决定频率）
- 高频广播必须使用服务端订阅索引取候选会话：`/ws/v1/market` 按 `channel + symbol` 索引，`/ws/v1/trading` 按 `userId + channel` 与 `admin channel` 索引；不得在单条 tick / PnL / admin patch 推送路径全量扫描所有 WebSocket 会话。
- stale 行情：市场价格 key 过期后，服务端应向所有相关订阅客户端推送一次 stale 通知；`NO_QUOTE / ABNORMAL` 不刷新 WebSocket 可成交价格，休盘 tick 不推送

```json
{
  "type": "price.tick",
  "symbol": "EURUSD",
  "stale": true,
  "quoteStatus": "STALE",
  "qualityReason": "QUOTE_TIME_DRIFT_EXCEEDED",
  "ts": "2026-04-16T16:00:00Z"
}
```

## 12. 统一接口文档输出要求

每次 WebSocket 接口开发并测试通过后，必须同步更新：

- [FalconX统一接口文档](./FalconX统一接口文档.md)

要求：

- 由接口实现者负责更新
- 订阅请求、推送消息、错误消息、认证要求、重连约束都必须写入统一接口文档
- 未更新该文档，不视为接口任务完成
