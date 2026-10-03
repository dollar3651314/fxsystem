# falconx-trading-core-service

## 文档边界

本文件只记录 trading 域模块职责、当前已落地能力与当前边界。

- 项目阶段状态与验收结论以 `docs/setup/当前开发计划.md` 为准。
- 交易规则、数据库 owner、REST 契约、Kafka 契约、状态机、事务幂等等以专题规范为准。
- 本 README 记录“当前实现事实”，但不单独宣布 `Stage 7 / 7A` 已验收。

## 模块职责

`falconx-trading-core-service` 是 FalconX 一期 CFD 交易 owner 服务，负责：

- 账户与账本
- 业务入金入账
- 订单、持仓、成交
- 风控与行情驱动处理
- 交易域 Outbox / Inbox
- `Swap` 结算与逐仓增强子能力

## 当前已落地能力

### owner 基础闭环

- owner MySQL、Flyway、`MyBatis + XML Mapper`、Outbox / Inbox 已落地。
- 已落地 `wallet.deposit.confirmed / reversed` 与 `market.price.tick` 的正式消费入口。
- 已具备 `walletTxId` 幂等、Outbox / Inbox 基础链路。
- 已形成 `market.kline.update` 在 `trading-core-service` 侧的正式消费与 `t_inbox` 留痕。

### 北向接口

- `GET /api/v1/trading/accounts/me`
- `GET /api/v1/trading/swap-settlements`
- `GET /api/v1/trading/orders`
- `GET /api/v1/trading/trades`
- `GET /api/v1/trading/positions`
- `GET /api/v1/trading/ledger`
- `GET /api/v1/trading/liquidations`
- `POST /api/v1/trading/orders/market`
- `POST /api/v1/trading/positions/{positionId}/close`
- `POST /api/v1/trading/positions/{positionId}/margin`
- `PATCH /api/v1/trading/positions/{positionId}`

### 交易闭环事实

- 已具备业务入金入账、账户查询、市价开仓、手动平仓、TP/SL、强平执行的当前实现事实。
- `QuoteDrivenEngine` 当前负责最新行情快照、stale 检测、TP/SL / 强平扫描，以及 B-book 风险敞口美元换算的价格驱动刷新。
- 已落地 `net_exposure_usd`、`hedge_threshold_usd`、`t_hedge_log` 与阈值告警 / 恢复日志。

### `Swap` 当前事实

- 已落地 `Swap` 首版 owner 结算链路：从 `market-service` owner Redis 共享快照读取 `Swap rate`，按 `rollover_time` 定时扫描 `OPEN` 持仓，使用 `rollover ± stale.max-age` 窗口内的 fresh 有效价结算。
- 已通过 `t_ledger.biz_type=6/7` 与 `swap:{positionId}:{rolloverAt}` 完成幂等落账。
- 已落地 `GET /api/v1/trading/swap-settlements` 分页查询。
- 已落地低频关键业务事件 `falconx.trading.swap.settled`。

### 逐仓增强首批子范围

- 已完成 `POST /api/v1/trading/orders/market` 的 `marginMode` 北向显式化；未传默认 `ISOLATED`，显式 `CROSS` 返回 `40010`。
- 已完成 `POST /api/v1/trading/positions/{positionId}/margin`。
- 已完成 `t_ledger.biz_type=10(isolated_margin_supplement)` 的应用枚举、注释迁移与账务落账。
- 已完成追加保证金后的 `liquidationPrice` 重算。
- 已完成追加保证金、手动平仓、强平之间的 `SELECT FOR UPDATE` 与强平执行前二次校验。

## 当前验证事实

- 已补 `TradingSwapSettlementIntegrationTests`，覆盖多头收取、空头收入、stale 跳过后重试，以及账本幂等。
- 已补 `TradingControllerIntegrationTests`、`TradingUserQueryControllerIntegrationTests` 与 `KafkaTradingOutboxEventPublisherTests`，覆盖 `Swap` 明细接口、用户视角查询与事件出站。
- 已补交易闭环与风险闭环定向验证：手动平仓 stale / 缺报价 / 持仓不存在 / 非本人 / 重复平仓，TP/SL 空头方向规则与重复价格幂等，强平负净值保护与 `t_risk_exposure` 回滚。

## 模块联动

- `gateway -> trading-core-service`：驱动账户查询、历史查询、开仓、手动平仓、追加逐仓保证金和持仓风险参数修改。
- `market-service -> trading-core-service`：`falconx.market.price.tick` 驱动最新价、浮盈亏、TP/SL 与强平扫描；`falconx.market.kline.update` 当前形成低频正式消费与 `t_inbox` 审计事实。
- `wallet-service -> trading-core-service`：`falconx.wallet.deposit.confirmed / reversed` 驱动业务入金与回滚；成功入账后由 trading 继续通过 `trading.deposit.credited` 联动 `identity-service`。

## 当前边界与不应误写的内容

- 手动平仓、TP/SL、强平和逐仓增强首批子范围属于当前实现事实，但项目阶段完成结论仍以 `docs/setup/当前开发计划.md` 为准。
- 当前不把 `TradingHedgeAlertEvent` 写成 BBook 自营风控闭环，不把 A-book 对冲执行出口纳入一期目标，也不把 `CROSS` 模式写成已完成能力。
- 账户 / 订单 / 持仓 / 费用等用户侧实时推送端点尚未冻结正式 WebSocket 契约，不属于当前 trading 域已完成能力。
- 当前 `Swap` 仍严格依赖 `rollover ± stale.max-age` 窗口内的 fresh 报价；如需长时间中断后的精确历史补算，仍需单独冻结历史价格事实来源。

## 相关文档

- `docs/api/REST接口规范.md`
- `docs/api/FalconX统一接口文档.md`
- `docs/event/Kafka事件规范.md`
- `docs/domain/状态机规范.md`
- `docs/architecture/事务与幂等规范.md`
- `docs/process/逐仓模式改造方案.md`
- `docs/setup/当前开发计划.md`
