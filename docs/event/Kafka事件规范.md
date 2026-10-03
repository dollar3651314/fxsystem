# FalconX v1 Kafka 事件规范

## 1. 目标

统一 FalconX v1 的事件命名、消息信封、分区键、重试和幂等策略。

## 2. 基本原则

- 一期消息语义采用 `at-least-once`
- 消费者必须幂等
- 跨服务业务协作优先走事件
- 数据库状态变更与事件发布必须通过 `Outbox` 或等价可靠发布方案衔接

## 3. Topic 命名规范

统一格式：

- `falconx.<context>.<entity>.<event>`

示例：

- `falconx.market.price.tick`
- `falconx.market.kline.update`
- `falconx.wallet.deposit.detected`
- `falconx.wallet.deposit.confirmed`
- `falconx.wallet.deposit.reversed`
- `falconx.trading.order.created`
- `falconx.trading.order.filled`
- `falconx.trading.position.closed`
- `falconx.trading.liquidation.executed`

DLQ 统一格式：

- `<original-topic>.dlq`

## 4. 消息承载规范

一期当前运行时统一采用“Kafka headers 承载事件元数据，消息体只放业务 payload JSON”的传输口径：

- Kafka message body：只放业务 payload JSON
- Kafka message key：作为运行时 `partitionKey`
- Kafka headers：
  - `X-Event-Id`
  - `X-Event-Type`
  - `X-Event-Source`
  - `X-Trace-Id`

说明：

- 当前 Stage 6A 运行时没有把 `schemaVersion / occurredAt` 再重复塞进消息体；若后续需要补充，必须以加性方式扩展，不能破坏现有消费者对“body=payload JSON”的兼容假设
- 消费端默认从 Kafka headers 读取 `eventId / eventType / source / traceId`，再把消息体按目标 payload 契约直接反序列化
- 若业务需要显式记录分区键，应以 Kafka key 为准，不再在消息体里重复一份 `partitionKey`

运行时示例：

- Kafka key：`ETH:0xabc123`
- Kafka headers：
  - `X-Event-Id: evt-wallet-confirmed-0001`
  - `X-Event-Type: wallet.deposit.confirmed`
  - `X-Event-Source: falconx-wallet-service`
  - `X-Trace-Id: 9fbcf7c6f0d1467b`
- Kafka body：

```json
{
  "walletTxId": 3000001,
  "userId": 2000001,
  "chain": "ETH",
  "token": "USDT",
  "txHash": "0xabc123",
  "fromAddress": "0xsource",
  "toAddress": "0xplatform",
  "amount": "1500.00000000",
  "confirmations": 12,
  "requiredConfirmations": 12,
  "confirmedAt": "2026-04-16T16:05:00Z"
}
```

## 5. 分区键规范

按业务特性选 key，不强求全系统统一一个规则。

建议：

- 市场价格事件：按 `symbol`
- 钱包入金事件：按 `chain:txHash`
- 订单与持仓事件：按 `userId` 或 `orderId`

原则：

- 同一业务实体相关事件应尽量进入同一分区

## 6. 生产者规范

- 事件必须来自明确 owner 服务
- 业务表写入成功后再发布事件
- 禁止在业务逻辑里直接无保护地“先发消息后写库”
- 涉及关键状态变更时必须使用 `Outbox`

## 7. 消费者规范

消费者必须具备：

- 幂等处理
- 去重能力
- 重试能力
- 死信转移能力

最低要求：

- 使用 `eventId` 去重
- 业务唯一键二次保护
- 失败达到阈值后进入 DLQ

## 8. 重试规范

建议策略：

- 短暂错误：有限次重试
- 业务不可恢复错误：直接进入 DLQ

典型不可恢复错误：

- payload 缺字段
- schemaVersion 不支持
- 业务实体不存在且无法补偿

## 9. 事件版本规范

- 事件增加字段时优先向后兼容
- 破坏性变更必须升级 `schemaVersion`
- 消费者必须显式处理版本

## 10. 消费组命名规范

统一格式：

- `falconx.<service-name>.<context>-consumer-group`

示例：

- `falconx.trading-core-service.price-tick-consumer-group`
- `falconx.trading-core-service.deposit-confirmed-consumer-group`
- `falconx.identity-service.deposit-credited-consumer-group`

规则：

- 每个消费者逻辑功能使用独立消费组名称
- 不同实例同一功能共享同一消费组名称
- 消费组名称不随代码变更而随意修改（变更意味着重置 offset）

## 11. 当前一期关键主题

一期固定保留：

- `falconx.market.price.tick`（market-service 发布，trading-core-service 消费；Kafka 入口允许容器级失败重试）
- `falconx.market.kline.update`（market-service 发布，trading-core-service 消费并在 `t_inbox` 留痕）
- `falconx.wallet.deposit.detected`（wallet-service 发布）
- `falconx.wallet.deposit.confirmed`（wallet-service 发布，trading-core-service 消费）
- `falconx.wallet.deposit.reversed`（wallet-service 发布，trading-core-service 消费）
- `falconx.trading.deposit.credited`（trading-core-service 发布，identity-service 消费）
- `falconx.identity.kyc.reviewed`（identity-service 发布，trading-core-service 消费）

说明：

- `falconx.trading.deposit.credited` 是业务入金完成事实事件
- trading-core-service 完成入账后必须发布此事件
- identity-service 消费此事件并写入 `t_inbox` 做幂等留痕；若历史用户仍为 `PENDING_DEPOSIT`，归一化为 `ACTIVE`
- `falconx.identity.kyc.reviewed` 是 KYC 审核完成事实事件
- identity-service 在 admin approve / reject 提交事务后发布此事件
- trading-core-service 消费此事件后通过 `TradingNotificationApplicationService` 生成站内信并经 `CHANNEL_NOTIFICATIONS` 推送给目标用户；幂等键为 `submissionId`

后续由 `trading-core-service` 发布：

- `falconx.trading.order.created`
- `falconx.trading.order.filled`
- `falconx.trading.position.closed`
- `falconx.trading.liquidation.executed`
- `falconx.trading.swap.settled`
- `falconx.trading.account.mode.changed`（STAGE-14D1，用户级 margin mode 切换成功事实，见 §12.15）

补充约束：

- `Stage 7A` 首批逐仓增强里的"追加逐仓保证金"当前**不新增** Kafka topic / payload
- `POST /api/v1/trading/positions/{positionId}/margin` 的成功事实只落 owner MySQL（`t_account / t_position / t_ledger.biz_type=10`）和内存快照，不写 Outbox、不发业务 topic

### 11.1 Stage 7A 后续逐仓范围的事件冻结结论

本小节对应 [逐仓模式改造方案](../process/逐仓模式改造方案.md) §5.2 的 **D2 冻结结论**，为 Stage 7A 整阶段验收（`STAGE7A-ISOLATED-02`）锁死事件面。

- **当前未纳入，且在 Stage 7A 整阶段不新增**的 Kafka topic：
  - `falconx.trading.position.margin.supplemented`
  - `falconx.trading.position.margin.*` 下任何其它命名
- 追加逐仓保证金的审计链路固定为 `t_ledger.biz_type=10(isolated_margin_supplement)` + 持仓最新快照承载，不通过 Kafka 事件承载。
- 若未来确需新增上述 topic，必须回到 [逐仓模式改造方案](../process/逐仓模式改造方案.md) §5.2 修订 D2 决策后再回写本文件；实施阶段不得绕过。

### 11.2 STAGE-14C1 杠杆/MM 分级范围内的事件结论

- `falconx.trading.tier.changed`（tier 配置变更广播，供 `LeverageTierResolver` 缓存失效用）**C1 计划项、C2 仍未引入**：STAGE-14C2 console tier CRUD 落地后，trading-core 在写入（insert/update/软删）成功后由 `TradingTierAdminApplicationService` 直接调用 `LeverageTierResolver.invalidate(...)` 失效本进程缓存，**未引入任何 Kafka topic**。
- STAGE-14C1/C2 的 `t_symbol_leverage_tier` 运行时由 `LeverageTierResolver` 用 DB 查询 + 30s 本地缓存（C2 后写路径加进程内 invalidate，无事件驱动失效），不依赖任何 Kafka topic。
- **多实例口径**：当前为单进程 invalidate + 30s 惰性 TTL 兜底（master §8.3 容许）；多实例下其余实例最迟 30s 内由 TTL 过期惰性刷新对齐。跨实例事件驱动失效（如引入 `tier.changed`）留后续阶段，本阶段不实现。

## 12. 关键事件 Payload 约定

### 12.1 `falconx.wallet.deposit.detected`

用途：

- `wallet-service` 在识别到归属平台地址的原始链入金时发布
- 该事件代表“已发现链事实，但尚未最终确认”

推荐 payload：

```json
{
  "walletTxId": 3000001,
  "userId": 2000001,
  "chain": "ETH",
  "token": "USDT",
  "txHash": "0xabc123",
  "fromAddress": "0xsource",
  "toAddress": "0xplatform",
  "amount": "1500.00000000",
  "confirmations": 3,
  "requiredConfirmations": 12,
  "detectedAt": "2026-04-16T16:00:00Z"
}
```

字段要求：

- `walletTxId`：wallet owner 产出的稳定原始交易主键，跨服务幂等统一依据该字段
- `userId`：归属用户 ID
- `chain`：链标识
- `token`：入金币种
- `txHash`：链上哈希
- `fromAddress`：来源地址
- `toAddress`：目标地址
- `amount`：已按 token decimals 归一化后的业务金额，统一保留 `8` 位小数；禁止直接传 raw amount
- `confirmations`：当前确认数
- `requiredConfirmations`：所需确认数
- `detectedAt`：首次检测时间

分区键建议：

- `chain:txHash`

### 12.2 `falconx.wallet.deposit.confirmed`

用途：

- `wallet-service` 在原始链入金满足最终确认条件后发布
- `trading-core-service` 消费后执行业务入账

推荐 payload：

```json
{
  "walletTxId": 3000001,
  "userId": 2000001,
  "chain": "ETH",
  "token": "USDT",
  "txHash": "0xabc123",
  "fromAddress": "0xsource",
  "toAddress": "0xplatform",
  "amount": "1500.00000000",
  "confirmations": 12,
  "requiredConfirmations": 12,
  "confirmedAt": "2026-04-16T16:05:00Z"
}
```

字段要求：

- `walletTxId`：wallet owner 产出的稳定原始交易主键，trading-core-service 必须按该字段做业务入账幂等
- `userId`：归属用户 ID
- `chain`：链标识
- `token`：入金币种
- `txHash`：链上哈希
- `fromAddress`：来源地址
- `toAddress`：目标地址
- `amount`：已按 token decimals 归一化后的业务金额，统一保留 `8` 位小数；禁止直接传 raw amount
- `confirmations`：当前确认数
- `requiredConfirmations`：所需确认数
- `confirmedAt`：最终确认时间

分区键建议：

- `chain:txHash`

### 12.3 `falconx.wallet.deposit.reversed`

用途：

- `wallet-service` 在链回滚或原始入金失效时发布
- `trading-core-service` 可基于该事件触发回滚或人工补偿流程

推荐 payload：

```json
{
  "walletTxId": 3000001,
  "userId": 2000001,
  "chain": "ETH",
  "token": "USDT",
  "txHash": "0xabc123",
  "fromAddress": "0xsource",
  "toAddress": "0xplatform",
  "amount": "1500.00000000",
  "confirmations": 0,
  "requiredConfirmations": 12,
  "reversedAt": "2026-04-16T16:06:00Z"
}
```

字段要求：

- `walletTxId`：wallet owner 产出的稳定原始交易主键，trading-core-service 必须按该字段做回滚幂等
- `userId`：归属用户 ID
- `chain`：链标识
- `token`：入金币种
- `txHash`：链上哈希
- `fromAddress`：来源地址
- `toAddress`：目标地址
- `amount`：已按 token decimals 归一化后的业务金额，统一保留 `8` 位小数；禁止直接传 raw amount
- `confirmations`：当前确认数
- `requiredConfirmations`：所需确认数
- `reversedAt`：回滚时间

分区键建议：

- `chain:txHash`

### 12.4 `falconx.trading.deposit.credited`

用途：

- `trading-core-service` 在完成业务入账后发布
- `identity-service` 消费后写入 `t_inbox` 做幂等留痕；对历史 `PENDING_DEPOSIT` 用户执行 `ACTIVE` 归一化

推荐 payload：

```json
{
  "depositId": 1000001,
  "userId": 2000001,
  "accountId": 3000001,
  "chain": "ETH",
  "token": "USDT",
  "txHash": "0xabc123",
  "amount": "1500.00000000",
  "creditedAt": "2026-04-16T16:00:00Z"
}
```

字段要求：

- `depositId`：业务入金事实 ID
- `userId`：入金用户 ID
- `accountId`：入账账户 ID
- `chain`：链标识
- `token`：入金代币
- `txHash`：链上哈希
- `amount`：入账金额
- `creditedAt`：入账时间

分区键建议：

- `userId`

### 12.5 `falconx.trading.swap.settled`

用途：

- `trading-core-service` 在单笔 `Swap` 账本落账完成后发布
- 当前用于对账、通知或运营类下游的低频关键业务事件

推荐 payload：

```json
{
  "ledgerId": 1000001,
  "userId": 2000001,
  "positionId": 3000001,
  "symbol": "BTCUSDT",
  "side": "BUY",
  "settlementType": "SWAP_CHARGE",
  "amount": "1.00000000",
  "rate": "-0.00010000",
  "effectivePrice": "10000.00000000",
  "rolloverAt": "2026-04-21T21:59:59Z",
  "quoteTs": "2026-04-21T21:59:58Z",
  "settledAt": "2026-04-21T21:59:59Z"
}
```

字段要求：

- `ledgerId`：交易账本主键，作为下游对账锚点
- `userId`：归属用户 ID
- `positionId`：结算持仓 ID
- `symbol`：交易品种
- `side`：持仓方向，固定为 `BUY / SELL`
- `settlementType`：固定为 `SWAP_CHARGE / SWAP_INCOME`
- `amount`：结算金额，始终传正数
- `rate`：本次结算使用的配置费率
- `effectivePrice`：本次结算使用的有效价格；多头取 `bid`，空头取 `ask`
- `rolloverAt`：本次结算所属 `rollover` 时点
- `quoteTs`：本次结算使用的报价时间
- `settledAt`：账本落账时间

分区键建议：

- `userId`

### 12.6 `falconx.market.price.tick`

用途：

- `market-service` 在标准化一条实时报价后发布
- `trading-core-service` 消费后驱动高频报价链路

运行时 headers：

- Kafka key：`symbol`
- `X-Event-Id`：事件唯一标识
- `X-Event-Type`：固定为 `market.price.tick`
- `X-Event-Source`：固定为 `falconx-market-service`
- `X-Trace-Id`：沿当前链路透传

运行时 body：

```json
{
  "symbol": "EURUSD",
  "bid": "1.08321",
  "ask": "1.08331",
  "mid": "1.08326",
  "mark": "1.08326",
  "ts": 1776676530.123,
  "source": "TM_QUOTE",
  "stale": false,
  "quoteStatus": "FRESH",
  "qualityReason": null
}
```

字段要求：

- `symbol`：平台展示和交易 symbol。默认 LP 导入品种为 1:1 映射；自定义平台 symbol 由 market-service 根据 `t_symbol_quote_mapping` 从 LP 源 symbol 转换后发布。进入实时发布链路的映射必须 `enabled=1 AND lp_subscribe_enabled=1`，且 `source_symbol` 必须存在于 `t_symbol.status=1`；事件发布不得按 `.p / .c / .f` 或其他后缀做特殊过滤。Kafka 消费方不得再按 LP 源 symbol 解释
- `bid`：卖出参考价
- `ask`：买入参考价
- `mid`：`(bid + ask) / 2`
- `mark`：兼容标记价字段；交易侧做逐仓估值、TP/SL、强平与账户浮盈亏时，仍应按方向从 `bid / ask` 解析有效标记价
- `ts`：当前运行时按 Jackson 数值时间输出，语义为 Unix epoch seconds，可带小数秒；消费方不得把它强绑成字符串格式
- `source`：当前主行情源固定为 `TM_QUOTE`
- `stale`：报价是否已超时
- `quoteStatus`：报价质量状态，当前取值为 `FRESH / STALE / NO_QUOTE / MARKET_CLOSED / ABNORMAL`；只有 `FRESH` 允许交易侧进入成交、TP/SL 或强平触发
- `qualityReason`：不可成交原因，当前包括 `QUOTE_TIME_DRIFT_EXCEEDED / UNCHANGED_TOO_LONG / MARKET_CLOSED / NON_POSITIVE_PRICE / BID_ASK_CROSSED`；可为空

兼容性要求：

- `quoteStatus / qualityReason` 为向后兼容新增字段，消费方必须允许未知字段。
- 历史消息缺少 `quoteStatus` 时，消费方按 `stale=false -> FRESH`、`stale=true -> STALE` 推断。
- `STALE / NO_QUOTE / ABNORMAL` tick 只用于交易侧快照和审计，不得触发成交、TP/SL 或强平。
- `MARKET_CLOSED` 表示休盘判定；当前 market-service 在休盘时跳过 Redis 最新价、ClickHouse、Kafka、WebSocket 和 K 线写入，因此该状态主要用于内部返回对象和后续兼容。

分区键建议：

- `symbol`

消费执行要求：

- `trading-core-service` 必须先从 Kafka listener 线程切换到自管执行器，再进入报价驱动的订单、TP/SL、强平与提醒处理链路。
- 消费执行器必须按 `symbol` 分区：同一 `symbol` 的 tick 保持串行处理，不同 `symbol` 可并行处理。
- 运行时并发度由 `falconx.trading.kafka.market-price-tick-worker-count` 控制；吞吐提升依赖 Kafka topic 分区数与该配置匹配，不能通过破坏同 symbol 顺序换取并发。
- 挂单与价格提醒扫描前必须先经过 trading-core 本地活跃 `symbol` 索引门禁；该索引只用于跳过无活跃触发器的 DB 查询，真实触发资格仍以 MySQL `PENDING / ACTIVE` 状态和 CAS 更新为准。

### 12.7 `falconx.identity.kyc.reviewed`

用途：

- `identity-service` 在 admin 审核完成（APPROVED / REJECTED）且事务提交后发布
- `trading-core-service` 消费后通过 `TradingNotificationApplicationService` 写入 `t_notification` 并经 `CHANNEL_NOTIFICATIONS` 推送给目标用户

运行时 headers：

- Kafka key：`userId`
- `X-Event-Id`：事件唯一标识（建议 `kyc-reviewed-{submissionId}`）
- `X-Event-Type`：固定为 `identity.kyc.reviewed`
- `X-Event-Source`：固定为 `falconx-identity-service`
- `X-Trace-Id`：沿当前链路透传

运行时 body：

```json
{
  "submissionId": 5000001,
  "userId": 2000001,
  "result": "APPROVED",
  "kycLevel": 1,
  "reviewerId": 1001,
  "reviewAt": "2026-05-14T10:30:00Z",
  "rejectReason": null
}
```

字段要求：

- `submissionId`：KYC 提交记录主键；消费方幂等键
- `userId`：审核目标用户 ID；同时作为 Kafka 分区键
- `result`：固定为 `APPROVED` 或 `REJECTED`
- `kycLevel`：审核通过后的等级（一期固定为 `1`）；`REJECTED` 时为 `0`
- `reviewerId`：审核 admin 用户 ID
- `reviewAt`：审核完成时间（UTC ISO-8601）
- `rejectReason`：拒绝原因；`APPROVED` 时为 `null`

消费方契约：

- trading-core-service 消费组：`falconx.trading-core-service.kyc-reviewed-consumer-group`
- 幂等键：`relatedKey = 'kyc.reviewed' + relatedId = submissionId`；`TradingNotificationRepository` 必须先按此键查重
- 站内信级别映射：`APPROVED → INFO`（标题 "KYC 已通过"）、`REJECTED → WARN`（标题 "KYC 未通过"，body 含 `rejectReason`）
- 失败重试：达阈值进入 `falconx.identity.kyc.reviewed.dlq`

分区键建议：

- `userId`

---

### 12.8 `falconx.trading.withdraw.requested`

> STAGE-7-WITHDRAW Phase 0 冻结，2026-05-14。

owner / 生产者：

- `trading-core-service`

消费者：

- 暂无（保留作为审计 + admin push 通道；阶段 7 实施时由 console 可订阅做"待办计数"）

语义：

- 用户提交出金（写 `t_withdraw_order` status=COOLING 完成）后 Outbox 发布
- `at-least-once`，按 `withdrawId` 幂等去重

Headers：

- `X-Event-Id`：建议 `withdraw-requested-{withdrawId}`
- `X-Event-Type`：固定 `trading.withdraw.requested`
- `X-Event-Source`：固定 `falconx-trading-core-service`
- `X-Trace-Id`：透传

Payload（JSON 字符串，UTF-8）：

```json
{
  "withdrawId": 50000001,
  "userId": 2000001,
  "amount": "100.50",
  "currency": "USDT",
  "network": "ERC20",
  "targetAddress": "0xabc...",
  "coolingUntil": "2026-05-14T07:08:33Z",
  "requireDelayed": false,
  "kycLevel": 1,
  "requestedAt": "2026-05-14T05:08:33Z"
}
```

分区键：`userId`。

---

### 12.9 `falconx.trading.withdraw.reviewed`

owner / 生产者：

- `trading-core-service`

消费者：

- `wallet-service`（在 APPROVED 后异步广播链上 tx）

语义：

- admin approve / reject 完成事务后通过 afterCommit 发布
- `at-least-once`；消费方按 `withdrawId` 幂等

Payload：

```json
{
  "withdrawId": 50000001,
  "userId": 2000001,
  "amount": "100.50",
  "currency": "USDT",
  "network": "ERC20",
  "targetAddress": "0xabc...",
  "result": "APPROVED",
  "delayedUntil": null,
  "reviewerId": 1001,
  "reviewAt": "2026-05-14T05:30:00Z",
  "reviewNote": null,
  "rejectReason": null
}
```

字段要求：

- `result`：`APPROVED / APPROVED_DELAYED / REJECTED`
- `delayedUntil`：仅 `APPROVED_DELAYED` 非空（审核时间 + 6h）；wallet 必须在此时间之后才广播
- `rejectReason`：仅 `REJECTED` 非空

消费方契约：

- wallet-service 消费组：`falconx.wallet-service.withdraw-reviewed-consumer-group`
- 仅消费 `result=APPROVED / APPROVED_DELAYED` 的事件；`REJECTED` 仅作为审计 channel
- `APPROVED_DELAYED` 必须延迟到 `delayedUntil` 之后才广播
- 失败重试：达阈值进入 `falconx.trading.withdraw.reviewed.dlq`

分区键：`userId`。

---

### 12.10 `falconx.wallet.withdraw.broadcast`

owner / 生产者：

- `wallet-service`

消费者：

- `trading-core-service`（更新 `t_withdraw_order.status=PROCESSING` + 落 `tx_hash`）

语义：

- KmsSigner 签名 + 链上广播成功后立即发布；先于链上确认
- `at-least-once`；消费方按 `withdrawId` 幂等

Payload：

```json
{
  "withdrawId": 50000001,
  "userId": 2000001,
  "network": "ERC20",
  "txHash": "0xdef456...",
  "nonce": 12,
  "gasFeeUsd": "0.50",
  "broadcastAt": "2026-05-14T07:10:05Z"
}
```

消费方契约：

- trading-core 消费组：`falconx.trading-core-service.withdraw-broadcast-consumer-group`
- 幂等键：`withdrawId`
- 失败重试：达阈值进入 `falconx.wallet.withdraw.broadcast.dlq`

分区键：`userId`。

---

### 12.11 `falconx.wallet.withdraw.confirmed`

owner / 生产者：

- `wallet-service`

消费者：

- `trading-core-service`（结算余额：`t_account.frozen -= amount` + `t_account.balance -= amount` + `t_ledger.biz_type=16 withdraw_settle` + `t_withdraw_order.status=COMPLETED`）

语义：

- 链上确认达到 `min_confirmations`（ERC20 12 块 / TRC20 19 块）后发布
- `at-least-once`；消费方按 `withdrawId` 幂等

Payload：

```json
{
  "withdrawId": 50000001,
  "userId": 2000001,
  "network": "ERC20",
  "txHash": "0xdef456...",
  "blockNumber": 19567890,
  "confirmations": 12,
  "confirmedAt": "2026-05-14T07:15:30Z"
}
```

消费方契约：

- trading-core 消费组：`falconx.trading-core-service.withdraw-confirmed-consumer-group`
- 幂等：`relatedKey = 'withdraw.confirmed' + relatedId = withdrawId`
- 站内信：写 `t_notification` (level=INFO, title="出金已完成")
- 失败重试：达阈值进入 `falconx.wallet.withdraw.confirmed.dlq`

分区键：`userId`。

---

### 12.12 `falconx.wallet.withdraw.failed`

owner / 生产者：

- `wallet-service`

消费者：

- `trading-core-service`（回滚冻结：`t_account.frozen -= amount` + `t_ledger.biz_type=17 withdraw_refund_chain_failed` + `t_withdraw_order.status=FAILED`）

语义：

- 链上签名 / 广播 / 确认任一阶段失败发布；包含 nonce 冲突、receipt revert、超时
- `at-least-once`；消费方按 `withdrawId` 幂等

Payload：

```json
{
  "withdrawId": 50000001,
  "userId": 2000001,
  "network": "ERC20",
  "txHash": "0xdef456...",
  "failureCode": "20012",
  "failureReason": "WITHDRAW_TX_TIMEOUT 30 min",
  "failedAt": "2026-05-14T07:40:00Z"
}
```

字段要求：

- `failureCode`：wallet `2xxxx` 段错误码（`20010 / 20011 / 20012 / 20013 / 20014`）
- `failureReason`：人可读原因，最大 512 字符
- `txHash`：广播前失败时为 `null`

消费方契约：

- trading-core 消费组：`falconx.trading-core-service.withdraw-failed-consumer-group`
- 幂等：`relatedKey = 'withdraw.failed' + relatedId = withdrawId`
- 站内信：写 `t_notification` (level=WARN, title="出金失败", body 含 failureReason)
- 失败重试：达阈值进入 `falconx.wallet.withdraw.failed.dlq`

分区键：`userId`。

---

### 12.13 `falconx.identity.user.registered`

> STAGE-5-WALLET-PROVISION 在 commit `f91fcd6`（2026-05-09）已落地；R2 二轮契约回填于 2026-05-15。

owner / 生产者：

- `identity-service`

消费者：

- `wallet-service`（消费后调 `WalletAddressAllocationApplicationService.ensureDefaultUsdtDepositAddresses` 幂等派生 TRC20 / ERC20 入金地址）

语义：

- `identity-service` 在用户注册事务 afterCommit 通过 `IdentityKafkaEventPublisher.publishUserRegistered` 发布
- `at-least-once`；消费方按 `t_wallet_address` 主键唯一约束去重（`user_id + chain + token`）
- Kafka 抖动导致发布失败时仅日志，不回滚注册事实——避免 Kafka 故障让用户注册失败；漏事件由 wallet DLQ + 后台运营手动重试兜底

运行时 headers（**当前实现尚未使用 `KafkaEventMessageSupport`，headers 部分为 P2 待办**）：

- Kafka key：`userId`
- 期望 headers（后续标准化）：`X-Event-Id` = `user-registered-{userId}`、`X-Event-Type` = `identity.user.registered`、`X-Event-Source` = `falconx-identity-service`、`X-Trace-Id` 透传
- 当前实现：headers 暂无，相关信息冗余写在 payload body 内的 `eventId` / `eventType` 字段

运行时 body：

```json
{
  "eventId": "user-registered-2000001",
  "eventType": "identity.user.registered",
  "userId": 2000001,
  "uid": "u8a3f7d2c",
  "email": "alice@example.com",
  "registeredAt": "2026-05-15T03:08:00Z"
}
```

字段要求：

- `eventId`：固定格式 `user-registered-{userId}`；同时作为 DLQ `event_id` 列写入（消费失败入 DLQ 时去重键）
- `eventType`：固定为 `identity.user.registered`
- `userId`：注册用户 ID（雪花，long）；同时作为 Kafka 分区键
- `uid`：用户公开 UID（短码字符串）
- `email`：注册邮箱（用于 DLQ 列表识别 + 运营反查）
- `registeredAt`：注册完成时间（UTC ISO-8601）

消费方契约：

- wallet-service 消费组：`falconx.wallet-service.user-registered-consumer`
- 幂等键：`ensureDefaultUsdtDepositAddresses` 内部基于 `t_wallet_address` 主键（user_id+chain+token）唯一约束去重；不需要单独 inbox 表
- 失败语义分流：
    - `WALLET_ADDRESS_ALLOCATION_FAILED`（xpub 缺失等配置错误）：consumer 内部捕获并落 `t_wallet_address_provision_dlq` 表（status=PENDING）后**吞掉**，避免无限重试堵塞 partition；运营通过 console DLQ 列表 + 重试按钮恢复
    - 其他业务异常 / `RuntimeException`：rethrow 走 Spring Kafka 默认重试 + 最终进 `falconx.identity.user.registered-dlt` Kafka DLT
- payload 解析失败（坏消息）：不重试，直接放弃；不阻塞 partition

分区键：`userId`。

R2 二轮已知技术债（与 [管理端接口规范 §11.6](../api/管理端接口规范.md) 同口径）：

1. identity 未使用 `IdentityUserRegisteredEventPayload` contract record（仍用 `LinkedHashMap → JSON`）
2. identity 未使用 `KafkaEventMessageSupport` headers
3. identity 未使用 Outbox 模式

后续如需统一化在新一轮专项中处理；当前 DLQ 兜底机制完整。

---

### 12.14 `falconx.market.fx.rate.update`

> STAGE-14A FX 数据源，2026-05-29 落地。

owner / 生产者：

- `market-service`（`FxRateKafkaPublisher`）

消费者：

- `trading-core-service`（STAGE-14B 已接入，2026-05-29）：trading 侧 `FxRateService` 经此 topic 增量刷新内存 FX rate（RPC bootstrap + Kafka 增量 + USD pivot 交叉）；消费组 `falconx-trading-fx-rate`。算法层 FX 真源统一为 `FxRateService`（不再走 `CurrencyConverter`）
- `console-service`（admin FX rate 监控）：**STAGE-14E2（2026-06-02）按 REST 5s 轮询实现**，不消费本 topic——console `AdminMarketFxController` 透传 market internal RPC `GET /internal/v1/market/fx/rates`，前端 `FxRateMonitorPage` 每 5s 轮询并依 `eventTimeMillis` 与当前时间差前端判 stale。master §7.5 设想的 `admin.fx.rate.update` 1Hz WebSocket 推送 channel 为**后续 refinement（defer，非阻断）**，本期未实现专用推送帧；待 refinement 落地后再由 console 消费本 topic 并经 admin WS 转推

语义：

- `FxRateKafkaPublisher` 按 1Hz 节流（TTL 1000ms）从 `FxRateService.snapshotAll()` 取当前 Redis 快照后批量发布
- 每对 base/quote 对应一条消息；同一发布周期内相同 pair 只取最新快照
- `at-least-once`；消费方按 `baseCurrency + quoteCurrency + eventTimeMillis` 组合幂等
- 节流间隔可通过 `falconx.market.fx.kafka-throttle-interval-ms`（默认 1000）admin 调整

运行时 headers：

- Kafka key：`baseCurrency/quoteCurrency`（例：`USD/JPY`）
- `X-Event-Id`：建议 `fx-rate-{baseCurrency}-{quoteCurrency}-{eventTimeMillis}`
- `X-Event-Type`：固定 `market.fx.rate.update`
- `X-Event-Source`：固定 `falconx-market-service`
- `X-Trace-Id`：透传

运行时 body：

```json
{
  "baseCurrency": "USD",
  "quoteCurrency": "JPY",
  "rate": "153.84200000",
  "eventTimeMillis": 1748476800000,
  "sourceLpCode": "TM",
  "sourceSymbol": "USDJPY"
}
```

字段要求（`FxRateSnapshotPayload`）：

- `baseCurrency`：基础货币代码（ISO 4217，大写）
- `quoteCurrency`：计价货币代码（ISO 4217，大写）
- `rate`：当前汇率，统一保留 8 位小数（`DECIMAL(20,8)`）；1 `baseCurrency` = `rate` `quoteCurrency`
- `eventTimeMillis`：LP tick 到达时间（Unix 毫秒），非发布时间；消费方用于判断数据新鲜度
- `sourceLpCode`：行情来源 LP 代码（如 `TM`）；交叉换算派生的汇率固定为 `CROSS`
- `sourceSymbol`：LP 原始 symbol（如 `USDJPY`）；交叉换算派生时为 `{BASE}{QUOTE}_CROSS`

错误码：

- `60011` `FX_RATE_STALE`：由 `FxRateStaleDetector` 在 30s 内未收到更新时发出 WARN 告警日志；不通过 Kafka 发布，监控 only

分区键建议：

- `baseCurrency/quoteCurrency`

### 12.15 `falconx.trading.account.mode.changed`

> STAGE-14D1 用户级 margin mode 切换，2026-06-01 落地（master §7.2）。

owner / 生产者：

- `trading-core-service`（`MarginModeSwitchApplicationService`，切换成功后在同一 `@Transactional` 内经 **Outbox** 投递；Outbox `event_type=trading.account.mode.changed`，`resolveTopic` 加 `falconx.` 前缀 → `falconx.trading.account.mode.changed`）

消费者：

- `console-service`：审计（margin mode 变更轨迹，STAGE-14D 后续接入）
- `identity-service`：触发 ACCOUNT_MODE_CHANGED 通知用途（D1 内通知已由 trading 侧 V34 模板直接 `notificationService.send` 落库站内信，跨服务消费按需启用）

语义：

- 仅在切换闸门全过、`t_account.margin_mode` 实际变更落库后发布（同事务，rollback 一并回滚，与状态变更原子）
- `at-least-once`；消费方按 `userId + changedAtMillis` 组合幂等

运行时 body（`AccountMarginModeChangedEventPayload`，位于 `falconx-trading-contract`）：

```json
{
  "userId": 990004001,
  "oldMode": "ISOLATED",
  "newMode": "CROSS",
  "changedAtMillis": 1748764775000,
  "coolingUntilMillis": 1748765075000
}
```

字段要求：

- `userId`：切换 margin mode 的用户 ID
- `oldMode` / `newMode`：切换前 / 后 margin mode（`ISOLATED` / `CROSS`）
- `changedAtMillis`：切换完成时间（epoch millis）
- `coolingUntilMillis`：冷静期结束时间（epoch millis，= changedAt + 5min）；null 表示无冷静期

分区键建议：

- `userId`（同一用户的 mode 变更保序）

> 注：D1 默认 `cross_mode.enabled=false`，切到 CROSS 被 30088 gated；该 topic 在 D1 实际可发布的是 CROSS→ISOLATED 或（开关打开后）ISOLATED→CROSS。CROSS 强平/实时 MM 留 D2。

### 12.16 `falconx.trading.position.closed` / `falconx.trading.liquidation.executed` 的 `close_reason` 扩展（STAGE-14D2）

> STAGE-14D2 CROSS 账户级强平 + 实时 MM，2026-06-01 落地（master §6.3）。本节只登记 D2 对既有平仓/强平事件 `close_reason` 维度的扩展，不新增 topic。

owner / 生产者：

- `trading-core-service`（`TradingPositionCloseApplicationService.closePositionByTrigger` 落账 + `TradingPositionClosePostProcessConsumer` 后处理发事件）

`close_reason` 枚举（`TradingPositionCloseReason`）扩 `CROSS_STOP_OUT`：

| close_reason | 触发源 | 落账口径 |
|---|---|---|
| `MANUAL` | 用户手动平仓 | 正常平仓 |
| `TAKE_PROFIT` / `STOP_LOSS` | TP/SL 价格触发 | 正常平仓 |
| `LIQUIDATION` | ISOLATED 单仓 liqPrice / 单仓 MarginLevel ≤ stopOut 双触发 | LIQUIDATED 状态 + `t_ledger.biz_type=9` + `t_liquidation_log` |
| `CROSS_STOP_OUT`（**D2 新增**） | **CROSS 账户级 MarginLevel ≤ stopOut**（单仓 `liquidation_price=null`，按账户级 ML 判据，浮亏最大优先逐仓平） | 同 `LIQUIDATION` 落账（LIQUIDATED 状态 + `biz_type=9` + `t_liquidation_log`，`liquidation_price` 为 NULL，见数据库设计 V35） |

语义：

- `CROSS_STOP_OUT` 与 `LIQUIDATION` 在持仓终态/账本/强平日志口径一致，区别在触发源为**账户级 MarginLevel**而非单仓价格；`closePositionByTrigger` 内 `SELECT FOR UPDATE` 二次价格校验对 `CROSS_STOP_OUT` 放开（账户级触发不依赖单仓 liqPrice 命中，否则被静默吞掉——放开 C1 latent 耦合）。
- **逐仓 `POSITION_LIQUIDATED` 站内信去重**：CROSS 账户级强平的逐仓 close **不再**逐仓发 `POSITION_LIQUIDATED`，改由 `CrossLiquidationOrchestrator` 在一轮逐仓强平结束后发**一条**账户级 `CROSS_STOP_OUT_TRIGGERED` 汇总通知（params：`marginLevel`=触发时账户 ML / `count`=强平仓数 / `symbols`=强平 symbol 逗号清单，`relatedKey=ACCOUNT`，`relatedId=accountId`，V36 模板 seed）。
- `CROSS_STOP_OUT_TRIGGERED` 为站内信通知模板（V36），非 Kafka topic；发送失败仅 warn，不阻断强平主流程。
