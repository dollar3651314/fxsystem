# FalconX v1 REST 接口规范

## 1. 目标

统一 FalconX v1 所有 HTTP API 的路径、请求、响应、错误和幂等规则。

适用服务：

- `falconx-gateway`
- `falconx-identity-service`
- `falconx-market-service`
- `falconx-trading-core-service`
- `falconx-wallet-service`

## 2. 路径规范

外部接口统一使用：

- `/api/v1/**`

示例：

- `/api/v1/auth/register`
- `/api/v1/auth/login`
- `/api/v1/auth/logout`
- `/api/v1/market/symbols`
- `/api/v1/market/quotes/{symbol}`
- `/api/v1/market/quotes/{symbol}/history`
- `/api/v1/market/klines/{symbol}`
- `/api/v1/trading/orders`
- `/api/v1/trading/positions`
- `/api/v1/wallet/deposit-addresses/ensure`

内部接口规则：

- 一期默认不对业务协作开放常规 `/internal/**` 接口
- 若后续确实需要内部 HTTP 调用，再按服务 owner 单独增加

### 2.1 market-service 首页品种列表价格语义

`GET /api/v1/market/symbols` 用于首页和品种列表首屏展示，必须与交易成交价语义隔离：

- 返回列表只来自 market owner 当前用户组 `X-User-Group-Code` 在 `t_symbol_group_visibility` 中可见的 platform symbol；缺省组为 `default`
- 返回的 `bid` / `ask`（及 `/api/v1/market/quotes` 对外报价）为**已含用户组加点**的展示价：market 按 `X-User-Group-Code` 叠加 `t_symbol_group_markup` 的 bid/ask 绝对加点（STAGE-12）；REST `/quotes` 端点对称 WS 同时返回 `baseBid/baseAsk/baseMid/hasMarkup` 基准价字段（commit `90e59690`）。缺省组 `default` 加点为 0 时与基准价一致。交易侧开仓 fillPrice 复用同一加点并冻结到持仓，全生命周期一致（见 [WebSocket 接口规范 §6.1](../api/WebSocket接口规范.md)）
- `symbol` 是平台展示和交易 symbol。默认 LP 快照导入的 symbol 保留 LP 原始代码与普通市场后缀，例如 `AAPL.NAS`；自定义 symbol 如 `AAAUSD / XAU100` 可通过 `t_symbol_quote_mapping` 映射到 LP 源。后端订阅上游 LP 时使用 `source_lp_code + source_symbol` 选定源，再映射回 `platform_symbol` 推送到页面；接口不按 `.p / .c / .f` 或其他后缀做特殊限制，是否展示、订阅和报价只由源 `t_symbol.lp_code + symbol + status`、`t_symbol_quote_mapping` 启停与 `t_symbol_group_visibility` 可见性决定
- `priceStatus=LIVE`：来自 `falconx:market:price:{symbol}` 短 TTL 最新价，表示当前展示价仍在实时窗口内
- `priceStatus=REFERENCE`：来自 `falconx:market:last-valid-price:{symbol}` 最后有效参考价，只能用于展示
- `priceStatus=MISSING`：当前无展示价格
- `tradable=false` 时前端不得展示为可成交状态；后端交易接口仍必须执行交易时间、stale 与缺价校验
- 休盘或非交易时段不得因为存在参考价而允许成交

### 2.2 market-service 历史 K 线查询语义

`GET /api/v1/market/klines/{symbol}` 用于交易终端图表初始化，只返回 market owner 写入 ClickHouse 的历史 K 线事实：

- `symbol` 必须存在于 `t_symbol_quote_mapping.platform_symbol`，且当前用户组可见、映射到 `t_symbol.status=1` 的 LP 源；不存在或不可见均返回 `30001`
- `interval` 默认 `1m`，只能取 `falconx.market.kline.intervals` 中配置的周期
- `limit` 默认 `200`，服务端限制在 `1..500`
- 响应按 `openTime` 升序返回，便于前端直接绘制
- 该接口只负责 K 线历史初始化；当前正在形成中的 K 线必须通过行情 WebSocket 的 `kline.{interval}` 推送覆盖，前端不得用 `price.tick` 合成 K 线
- 历史 K 线不得作为交易成交价依据；交易链路仍必须读取短 TTL 实时价并执行风控校验

### 2.2A market-service 历史报价 Tick 查询语义

`GET /api/v1/market/quotes/{symbol}/history` 用于交易终端 Tick 分时图初始化，只返回 market owner 写入 ClickHouse `quote_tick` 的历史报价事实：

- `symbol` 必须存在于 `t_symbol_quote_mapping.platform_symbol`，且当前用户组可见、映射到 `t_symbol.status=1` 的 LP 源；不存在或不可见均返回 `30001`
- `limit` 默认 `600`，服务端限制在 `1..1000`
- 响应按 `ts` 升序返回，便于前端直接绘制 bid / ask 区间
- 历史 Tick 只用于图表展示，不得作为交易成交价依据；交易链路仍必须读取短 TTL 实时价并执行风控校验
- 首屏后续实时跳动必须通过行情 WebSocket 的 `price.tick` 推送完成

## 3. 方法规范

- `GET`：查询
- `POST`：创建或触发业务动作
- `PUT`：整体更新
- `PATCH`：局部更新

一期尽量少用：

- `DELETE`

说明：

- 金融业务大多使用状态变更而不是物理删除

## 4. 请求头规范

推荐统一支持：

- `Authorization: Bearer <token>`
- `X-Request-Id`
- `X-Idempotency-Key`

规则：

- `X-Request-Id`：单次请求标识
- `X-Idempotency-Key`：写接口幂等键

补充规则：

- 前端和外部调用方不支持主动传入 `traceId`
- 一期默认忽略外部请求中的 `X-Trace-Id`
- `traceId` 由 `gateway` 在请求进入系统时统一生成
- 生成后的 `traceId` 只在系统内部透传，并通过响应头回传给调用方
- 入金地址申请接口通过 `(userId, chain)` 与数据库唯一约束保证幂等，不要求客户端传 `X-Idempotency-Key`

推荐响应头：

- `X-Trace-Id`

## 4.1 TraceId 规则

`traceId` 是一次系统内部业务链路的全局追踪号。

固定规则：

- 格式统一为 `32` 位小写十六进制
- 由 `gateway` 统一生成
- 所有下游服务必须透传
- 所有响应都应回传 `X-Trace-Id`
- 所有 Kafka 事件都应携带 `traceId`

禁止事项：

- 不允许前端指定 `traceId`
- 不允许沿用客户端传入的固定 `traceId`
- 不允许把 `traceId` 当作业务幂等键

## 5. 请求体规范

规则：

- 请求 DTO 使用明确字段名，不使用单字符缩写
- 金额、价格、数量统一使用字符串传输
- 时间统一使用 ISO-8601 字符串或明确的 UTC 时间戳
- 交易下单接口若支持持仓级 TP/SL，字段名固定为：
  - `takeProfitPrice`
  - `stopLossPrice`

原因：

- 避免前端 JSON 浮点精度问题

## 6. 响应体规范

统一响应格式：

```json
{
  "code": "0",
  "message": "success",
  "data": {},
  "timestamp": "2026-04-16T16:00:00Z",
  "traceId": "9fbcf7c6f0d1467b"
}
```

字段规则：

- `code`：业务码，成功固定为 `0`
- `message`：简要说明
- `data`：业务数据
- `timestamp`：响应时间
- `traceId`：链路标识

## 7. 错误码规范

建议分段：

- `1xxxx`：身份与认证
- `2xxxx`：钱包与入金
- `3xxxx`：市场数据
- `4xxxx`：交易
- `5xxxx`：风险
- `9xxxx`：系统错误

HTTP 状态码与业务码并存：

- HTTP 用于表达协议层结果
- `code` 用于表达业务层结果

### 7.1 一期保留错误码清单

#### `1xxxx` 身份与认证

- `10001`：Unauthorized
- `10002`：User Banned
- `10003`：Login Rate Limited
- `10004`：Register Rate Limited
- `10005`：Invalid Credentials
- `10006`：Refresh Token Invalid
- `10007`：User Frozen
- `10008`：User Already Exists
- `10009`：Email Format Invalid
- `10010`：Password Too Weak
- `10011`：User Not Activated（历史保留；注册后可直接登录，`PENDING_DEPOSIT` 不再作为登录拦截状态）
- `10012`：Trading Rate Limited
- `10013`：Global IP Rate Limited

#### `2xxxx` 钱包与入金

- `20001`：Wallet Address Not Found
- `20002`：Deposit Transaction Duplicated
- `20003`：Deposit Still Confirming
- `20004`：Unsupported Chain
- `20005`：Deposit Reversed
- `20006`：Wallet Address Allocation Failed

#### `3xxxx` 市场数据

- `30001`：Symbol Not Found
- `30002`：Price Source Stale Or Disconnected
- `30003`：Quote Not Available
- `30004`：Kline Not Ready
- `30005`：Market Data Write Failed

#### `4xxxx` 交易

- `40001`：Insufficient Margin
- `40002`：Order Rejected
- `40003`：Order Not Found
- `40004`：Position Not Found
- `40005`：Duplicate Client Order Id
- `40006`：Invalid Order State
- `40007`：Position Already Closed
- `40008`：Symbol Trading Suspended
- `40010`：Margin Mode Not Supported

#### `5xxxx` 风险

- `50001`：Leverage Exceeded
- `50002`：Position Limit Exceeded
- `50003`：Liquidation Triggered
- `50004`：Risk Config Missing
- `50005`：Risk Engine Busy

#### `9xxxx` 系统

- `90001`：Internal Error
- `90002`：Dependency Timeout
- `90003`：Event Publish Failed
- `90004`：Invalid Request Payload

规则：

- 一期新增错误码必须先补到本清单
- WebSocket 错误码沿用同一套业务码，不再单独发明编号

## 8. 分页规范

分页查询统一使用参数：

- `page`
- `pageSize`
- `sortBy`
- `sortOrder`

默认约束：

- `page` 默认 `1`
- `pageSize` 默认 `20`
- `pageSize` 上限 `100`

分页响应建议包含：

- `items`
- `page`
- `pageSize`
- `total`

## 9. 幂等规范

下面的外部写接口必须支持幂等：

- 注册
- 下单
- 平仓
- 地址申请

规则：

- 优先使用 `X-Idempotency-Key`
- 交易接口可结合 `clientOrderId`
- 幂等冲突时返回相同结果或明确错误

## 9.1 交易查询补充约定

`GET /api/v1/trading/accounts/me` 的查询语义固定如下：

- 账户余额字段读取 `t_account`
- 当前 OPEN 持仓可来自内存快照
- `unrealizedPnl` 必须基于当前 `markPrice` 动态计算
- 禁止把 `unrealizedPnl` 作为高频字段持久化到 MySQL

`GET /api/v1/trading/orders` 的查询语义固定如下：

- 只返回当前登录用户自己的订单
- 数据 owner 固定读取 `t_order`
- 首版只开放 `page / pageSize` 分页参数，不开放筛选条件和排序字段
- 返回结果按 `created_at DESC, id DESC` 排序
- 订单级费用通过 `fee` 字段返回；首版不单独新增 `/fees` 接口

`GET /api/v1/trading/trades` 的查询语义固定如下：

- 只返回当前登录用户自己的成交
- 数据 owner 固定读取 `t_trade`
- 首版只开放 `page / pageSize` 分页参数，不开放筛选条件和排序字段
- 返回结果按 `traded_at DESC, id DESC` 排序
- 成交级费用通过 `fee` 字段返回；成交类型固定为 `OPEN / CLOSE / LIQUIDATION`

`GET /api/v1/trading/positions` 的查询语义固定如下：

- 只返回当前登录用户自己的持仓历史
- 数据 owner 固定读取 `t_position`
- `accounts/me` 继续只负责账户快照与当前 `OPEN` 持仓；完整历史列表统一走 `/positions`
- 首版只开放 `page / pageSize` 分页参数，不开放筛选条件和排序字段
- 返回结果按 `updated_at DESC, id DESC` 排序
- 对于 `OPEN` 持仓，允许在查询时基于 Redis 最新报价动态补充 `markPrice / unrealizedPnl / quoteStale / quoteTs / quoteSource`
- 对于 `CLOSED / LIQUIDATED` 终态持仓，不得伪造新的 `unrealizedPnl`

`GET /api/v1/trading/ledger` 的查询语义固定如下：

- 只返回当前登录用户自己的账本流水
- 数据 owner 固定读取 `t_ledger`
- 首版只开放 `page / pageSize` 分页参数，不开放筛选条件和排序字段
- 返回结果按 `created_at DESC, id DESC` 排序
- 首版费用查询通过 `ORDER_FEE_CHARGED / SWAP_* / LIQUIDATION_PNL / REALIZED_PNL` 等账本流水体现，不单独新增 `/fees`

`GET /api/v1/trading/liquidations` 的查询语义固定如下：

- 只返回当前登录用户自己的强平记录
- 数据 owner 固定读取 `t_liquidation_log`
- 首版只开放 `page / pageSize` 分页参数，不开放筛选条件和排序字段
- 返回结果按 `created_at DESC, id DESC` 排序
- 返回字段至少覆盖强平价、触发价、真实亏损、手续费、释放保证金和平台兜底金额

`POST /api/v1/trading/orders/market` 的请求语义固定如下：

- `takeProfitPrice / stopLossPrice` 为可选字段
- `marginMode` 为可选字段；未传时应用层默认按 `ISOLATED` 执行
- 一期当前只接受 `marginMode=ISOLATED`
- 若客户端显式传入 `marginMode=CROSS`，返回 `40010: Margin Mode Not Supported`
- 下单前必须执行交易时间校验
- 交易时间校验只允许依赖 `market-service` 写入 Redis 的交易时间快照
- 若当前时刻不在可交易时段内，返回 `40008: Symbol Trading Suspended`
- `40008` 的触发场景至少包括：非交易时段、节假日全休、人工例外停盘
- 下单只允许使用 `quoteStatus=FRESH` 的报价；`STALE / NO_QUOTE / MARKET_CLOSED / ABNORMAL` 不得进入成交路径

### 交易域冻结规则说明（SPEC-TRD-001）

- 正式产品规则已冻结为“单用户单 `symbol` 单净持仓 + `One-Way + Isolated`”
- 净持仓模型下保留“每用户每 `symbol` 一个稳定 `positionId`”
- 同向下单视为加仓，反向下单视为减仓；若反向数量超过当前净仓，则先减到 `0`，剩余部分翻为反向净仓
- `POST /api/v1/trading/orders/market` 的后续正式实现必须以净持仓模型为准，不得再默认“一次开仓生成一条独立 `OPEN` 持仓”
- 有效最大杠杆固定为 `min(t_symbol.max_leverage, t_risk_config.max_leverage)`
- 手续费正式口径为双边收费；一期先以 `t_symbol.taker_fee_rate` 作为统一动态费率
- 在正式折算链路落地前，只允许 `quote_currency ∈ {USD, USDT, USDC}` 的品种进入交易放行
- 产品配置 owner 固定为 `market-service`；`trading-core-service` 后续必须消费 Kafka 下发的正式产品配置结果，不得继续以本地默认值或本地白名单作为正式产品口径
- 本节是规则冻结说明，不代表当前仓库已完成实现切换

`POST /api/v1/trading/positions/{positionId}/close` 的请求语义固定如下：

- 该接口用于手动平掉当前用户自己的 `OPEN` 持仓
- 本轮不引入请求体字段；平仓价固定读取 Redis 最新 `markPrice`
- 非交易时段、节假日全休或人工例外停盘阻塞手动平仓，返回 `40008: Symbol Trading Suspended`
- 若 Redis 无可用最新价，返回 `30003: Quote Not Available`
- 若最新价已 stale，返回 `30002: Price Source Stale Or Disconnected`
- 若最新价 `quoteStatus` 为 `NO_QUOTE`，返回 `30003: Quote Not Available`
- 若最新价 `quoteStatus` 为 `STALE / ABNORMAL / MARKET_CLOSED`，返回 `30002: Price Source Stale Or Disconnected` 或交易时间校验阶段的 `40008`
- 若持仓不存在或不属于当前用户，返回 `40004: Position Not Found`
- 若持仓已处于 `CLOSED / LIQUIDATED` 终态，返回 `40007: Position Already Closed`
- 平仓成功后必须在同一本地事务内完成账户结算、`t_ledger.biz_type=8`、持仓终态写入、`t_trade.trade_type=2`、`t_risk_exposure` 回补以及 `t_outbox.event_type=trading.position.closed` 写入
- 成功响应中的 `account.openPositions` 必须回显当前用户剩余的 `OPEN` 持仓视图，不能固定返回空数组
- 手动平仓不创建新的 `t_order` 记录
- 事务提交后必须移除 `OpenPositionSnapshotStore` 中的对应 OPEN 持仓快照

`POST /api/v1/trading/positions/{positionId}/margin` 的请求语义固定如下：

- 该接口用于为当前用户自己的 `OPEN` 持仓追加逐仓保证金
- 请求体固定为 `{ "amount": decimal }`
- 仅允许追加正数金额；请求体验证失败统一返回 HTTP `400 + 90004`
- 一期所有持仓当前都按 `ISOLATED` 运行，因此本接口不再额外引入 `marginMode` 入参
- 若可用余额不足，返回 `40001: Insufficient Margin`
- 若持仓不存在或不属于当前用户，返回 `40004: Position Not Found`
- 若持仓已处于 `CLOSED / LIQUIDATED` 终态，返回 `40007: Position Already Closed`
- 成功后账户语义固定为：
  - `balance` 不变
  - `frozen` 不变
  - `margin_used += amount`
- 成功后持仓保持 `OPEN`，但必须重算并持久化最新 `liquidationPrice`
- 成功后必须写入 `t_ledger.biz_type=10(isolated_margin_supplement)`，并保留完整账务快照
- 事务提交后必须执行 `OpenPositionSnapshotStore.upsert(position)`，保证报价驱动风控读取到最新 `margin / liquidationPrice`
- 本阶段不新增 Kafka topic / payload，不写 Outbox 业务事件

`PATCH /api/v1/trading/positions/{positionId}` 的契约语义固定如下：

- 该接口用于修改已有 `OPEN` 持仓的 `takeProfitPrice / stopLossPrice`
- 请求体允许只传一个字段；未传字段保持原值
- 若两者都显式传 `null`，表示清空 TP/SL
- 仅允许持仓 owner 修改自己的 `OPEN` 持仓
- 本阶段先冻结契约；真正实现与测试留到 `Stage 7`

## 9.2 钱包出金 API 冻结契约（STAGE-7-WITHDRAW Phase 0）

> 本节冻结阶段 7 出金链路的客户端 REST 契约。详细业务规则、状态机迁移、链上签名设计见 [出金状态机](../domain/状态机规范.md#7-出金状态机) 与 [Kafka 事件 §12.8-12.12](../event/Kafka事件规范.md)。

### 9.2.1 端点清单

| 路径 | 方法 | 用途 | 认证 |
| --- | --- | --- | --- |
| `/api/v1/me/withdraw` | POST | 提交出金申请 | Bearer |
| `/api/v1/me/withdraw` | GET | 分页查询当前用户出金记录 | Bearer |
| `/api/v1/me/withdraw/{id}` | GET | 查询单条出金详情（含链上 tx_hash） | Bearer |
| `/api/v1/me/withdraw/{id}/cancel` | POST | 冷静期内用户自助取消 | Bearer |
| `/api/v1/me/withdraw/whitelist` | GET | 查询出金地址白名单 | Bearer |
| `/api/v1/me/withdraw/whitelist` | POST | 新增出金地址白名单 | Bearer |
| `/api/v1/me/withdraw/whitelist/{id}` | DELETE | 删除出金地址白名单 | Bearer |

### 9.2.2 POST /api/v1/me/withdraw 提交出金

请求体：

```json
{
  "amount": "100.50",
  "currency": "USDT",
  "network": "ERC20",
  "targetAddress": "0xabc...",
  "whitelistId": "48275238449975296"
}
```

- `amount`：BigDecimal 字符串；最小 `$10`；保留 8 位小数（精度同 `t_account.balance`）
- `currency`：一期固定 `USDT`（不支持其他币种）
- `network`：一期固定 `ERC20 / TRC20`（与 owner 入金链类型一致）
- `targetAddress`：链上地址；必须通过白名单（默认强制白名单开关 `falconx.wallet.withdraw.whitelist-required=true`）
- `whitelistId`：白名单记录主键（用于地址签名确权；后端必须验证 `targetAddress == whitelist.address && whitelist.userId == 当前用户`）

业务规则：

- KYC 前置：`identity` 端 `kyc_level < 1` → `30043 WITHDRAW_KYC_REQUIRED`（客户端跳转 KYC 提交 drawer）
- 余额校验：`available = balance - frozen - margin_used`；`available < amount` → `30054 WITHDRAW_BALANCE_INSUFFICIENT`
- 单笔限额：`amount > $10000` → `30041 WITHDRAW_AMOUNT_EXCEEDS_SINGLE_LIMIT`
- 单日限额：当日 UTC 已审核通过 + COOLING/PENDING/APPROVED/APPROVED_DELAYED 累计 ≥ $30000 → `30042 WITHDRAW_AMOUNT_EXCEEDS_DAILY_LIMIT`
- 大额延迟：`amount >= $3000` → 自动进入 `APPROVED_DELAYED` 路径，审核后多加 6h 延迟；客户端响应显示 `delayedUntil`
- 网络合法性：`network` 不在 `ERC20 / TRC20` → `30044 WITHDRAW_NETWORK_UNSUPPORTED`
- 地址校验：`targetAddress` 格式不合 EIP-55 / TRC20 base58 → `30045 WITHDRAW_ADDRESS_INVALID`
- 白名单：未匹配 → `30046 WITHDRAW_ADDRESS_NOT_WHITELISTED`
- **陌生地址触发（阶段 6 KYC trigger 2，2026-05-19 落地）**：在 KYC 前置之后、白名单确权之前执行，若 `targetAddress`（按 ERC20 case-insensitive，TRC20 严格）∉ 该用户在对应链 `t_wallet_deposit_tx (status=CONFIRMED)` 的去重 `from_address` 集合 → `30056 WITHDRAW_UNFAMILIAR_ADDRESS`。trading-core 通过 `GET /internal/v1/wallet/console/deposits/from-addresses?userId=&chain=` 实时拉取（chain 映射：ERC20→ETH，TRC20→TRON）；返回 `historySize=0` 表示用户无任何入金历史。

成功响应（HTTP 200, code=0）：

```json
{
  "data": {
    "withdrawId": "48275238449975296",
    "userId": "48275236470263808",
    "amount": "100.50",
    "currency": "USDT",
    "network": "ERC20",
    "targetAddress": "0xabc...",
    "status": "COOLING",
    "coolingUntil": "2026-05-14T07:08:33Z",
    "delayedUntil": null,
    "rejectReason": null,
    "txHash": null,
    "createdAt": "2026-05-14T05:08:33Z"
  }
}
```

- 成功后立即冻结余额：`t_account.frozen += amount`；`t_ledger.biz_type=13 (withdraw_freeze)`（Phase 1 修订：原蓝图 12，因与 PENDING_ORDER_RELEASED 冲突已下移到 13；详见状态机 §7A 注释）
- 写 `t_withdraw_order` (status=0 COOLING) + `t_outbox.event_type=trading.withdraw.requested`（用于异步通知 admin）
- 客户端响应中不回显 `whitelistId`（防止猜测他人白名单 ID）

幂等：客户端必须传 `X-Idempotency-Key`（最长 64）；24h 内相同 key 直接返回原 `withdrawId`，不重复冻结余额

### 9.2.3 GET /api/v1/me/withdraw 列表

Query 参数：

- `page` 默认 `1`，`pageSize` 默认 `20`（最大 100）
- `status` 可选过滤：`COOLING / PENDING / APPROVED / APPROVED_DELAYED / PROCESSING / COMPLETED / FAILED / CANCELED / REJECTED`

排序：`created_at DESC, id DESC`

响应字段同 §9.2.2 + 分页 `page / pageSize / total / items[]`。

### 9.2.4 GET /api/v1/me/withdraw/{id} 详情

- 不存在或不属于当前用户：`30047 WITHDRAW_NOT_FOUND`
- 响应字段同 §9.2.2，额外包含 `confirmations`（区块确认数）+ `failureReason`（FAILED 状态时填）

### 9.2.5 POST /api/v1/me/withdraw/{id}/cancel 冷静期取消

业务规则：

- 仅允许 `status=COOLING` 调用；其他状态 → `30048 WITHDRAW_NOT_CANCELABLE`
- 不存在或不属于当前用户：`30047 WITHDRAW_NOT_FOUND`
- 成功后：`t_withdraw_order.status=CANCELED`；`t_account.frozen -= amount`；`t_ledger.biz_type=14 (withdraw_refund_cancel)`

响应：HTTP 200, code=0, data 同详情（status=CANCELED）。

### 9.2.6 GET / POST / DELETE /api/v1/me/withdraw/whitelist 白名单管理

- 用户最多 10 条 ACTIVE 白名单地址（`(user_id, network, address)` UNIQUE）→ 超限 `30051 WITHDRAW_WHITELIST_LIMIT_EXCEEDED`
- 重复添加 → `30052 WITHDRAW_WHITELIST_DUPLICATE`
- DELETE 不存在/非己 → `30053 WITHDRAW_WHITELIST_NOT_FOUND`
- 添加成功后**有 24h 冷静期**：地址必须 `added_at + 24h <= now` 才能用于提交出金（防止账户被劫持后即时添加+提现）；未到冷静期使用 → `30055 WITHDRAW_WHITELIST_COOLING_NOT_PASSED`
- POST 请求体：`{"address":"0x...","network":"ERC20","label":"我的 Ledger"}`；`label` 可选，最大 64 字符

### 9.2.7 出金错误码段（trading-core `3xxxx`）

| 错误码 | 含义 |
| --- | --- |
| `30040` | WITHDRAW_AMOUNT_INVALID 金额非法（≤0 或精度超 8） |
| `30041` | WITHDRAW_AMOUNT_EXCEEDS_SINGLE_LIMIT 单笔超 $10K |
| `30042` | WITHDRAW_AMOUNT_EXCEEDS_DAILY_LIMIT 单日累计超 $30K |
| `30043` | WITHDRAW_KYC_REQUIRED KYC 未通过 |
| `30044` | WITHDRAW_NETWORK_UNSUPPORTED network 非 ERC20/TRC20 |
| `30045` | WITHDRAW_ADDRESS_INVALID 地址格式非法 |
| `30046` | WITHDRAW_ADDRESS_NOT_WHITELISTED 未匹配白名单 |
| `30047` | WITHDRAW_NOT_FOUND 出金单不存在或非己 |
| `30048` | WITHDRAW_NOT_CANCELABLE 非 COOLING 状态不可取消 |
| `30049` | WITHDRAW_NOT_PENDING admin 审核时非 PENDING 状态 |
| `30050` | WITHDRAW_EMERGENCY_CANCEL_NOT_ALLOWED 非 APPROVED_DELAYED 状态 |
| `30051` | WITHDRAW_WHITELIST_LIMIT_EXCEEDED 超 10 条 ACTIVE |
| `30052` | WITHDRAW_WHITELIST_DUPLICATE 重复地址 |
| `30053` | WITHDRAW_WHITELIST_NOT_FOUND 白名单不存在或非己 |
| `30054` | WITHDRAW_BALANCE_INSUFFICIENT 可用余额不足 |
| `30055` | WITHDRAW_WHITELIST_COOLING_NOT_PASSED 白名单 24h 冷静期未到 |
| `30056` | WITHDRAW_UNFAMILIAR_ADDRESS 目标地址 ∉ 历史入金 from_address 集合（陌生地址 → 强制 KYC） |
| `30057-30069` | 预留 |

### 9.2.8 链上侧错误码段（wallet-service `2xxxx`）

| 错误码 | 含义 |
| --- | --- |
| `20010` | WITHDRAW_TX_NONCE_CONFLICT 同链 nonce 冲突，需重排 |
| `20011` | WITHDRAW_TX_BROADCAST_FAILED 链上广播失败 |
| `20012` | WITHDRAW_TX_TIMEOUT 等待确认超时 |
| `20013` | WITHDRAW_TX_REVERTED 链上 receipt status=0（合约 revert） |
| `20014` | WITHDRAW_SIGNER_UNAVAILABLE KmsSigner 不可用（KMS 故障） |
| `20015-20029` | 预留 |

---

## 10. 契约变更规范

- 对外接口变更必须先更新文档
- 破坏性变更必须升级版本或新增字段兼容
- 新增字段优先兼容式增加，不直接删除旧字段

## 11. 统一接口文档输出要求

每次 REST 接口开发并测试通过后，必须同步更新：

- [FalconX统一接口文档](./FalconX统一接口文档.md)

要求：

- 由接口实现者负责更新
- 文档内容必须与实际测试结果一致
- 未更新该文档，不视为接口任务完成
