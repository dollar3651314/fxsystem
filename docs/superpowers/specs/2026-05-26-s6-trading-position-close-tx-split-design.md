# S6 — TradingPositionCloseApplicationService 事务拆分设计

- Sprint 3 第 2 项
- Epic：[`2026-05-26-sprint-3-epic-overview.md`](./2026-05-26-sprint-3-epic-overview.md)
- 性能报告引用：[`docs/perf/性能分析-2026-05-25.md`](../../perf/性能分析-2026-05-25.md) §6 P0
- 状态：待审

## 1. 背景

**性能报告 §6 P0 原文**：

> **持仓平仓事务内写多张表原子性风险**
> - 文件：`TradingPositionCloseApplicationService.java:242-346`
> - 现象：`settlePositionExit()` 在一个事务内顺序执行: 账户结算 → 更新持仓 → 写成交 → 写外敞 → 写清算日志 → 写 Outbox。若任一表操作失败，整个事务回滚但内存快照已移除，导致持仓"数据库存在、内存消失"的幽灵状态。
> - 影响：强平时若 t_liquidation_log 满表或超时，整笔持仓退出失败但客户端认为成功，造成余额-持仓不一致。
> - 推荐修复：将 t_liquidation_log / t_trade 写入提取到 Outbox 事件，在事务提交后由消费者异步落盘；交易核心只原子保证账户 + 持仓 + Outbox。

## 2. 当前实现细节

`TradingPositionCloseApplicationService.settlePositionExit`（行 242-360）单 `@Transactional` 内顺序操作 **7 张表**：

| 序 | 表 | 操作 | 一致性需求 |
|---|---|---|---|
| 1 | `t_account` + `t_ledger` | 账户结算 + 账本流水（通过 `tradingAccountService.settlePositionExit`） | **强一致**（资金真源） |
| 2 | `t_position` | 状态机推进 CLOSED/LIQUIDATED + close_price + realized_pnl | **强一致**（持仓真源） |
| 3 | `t_trade` | 成交记录 INSERT | 审计，最终一致可接受？← **需用户决策** |
| 4 | `t_risk_exposure` | 敞口减少（`tradingRiskObservabilityService.applyClosePosition`） | 监控，下一 tick 会重算 |
| 5 | `t_liquidation_log` | 强平日志 INSERT（仅强平场景） | 审计，最终一致可接受 |
| 6 | `t_outbox` | `position.closed` 或 `position.liquidated` 事件 | **强一致**（事件保证） |
| 7 | `t_pending_order_trigger` | 级联撤 SL/TP 挂单（仅当持仓有关联） | 最终一致可接受 |

事务后（在事务外，已 commit 完成）：

- `registerSnapshotRemoval` — 内存快照清理（`afterCommit` 模式或直接调？需在实现时确认）
- `log.info/warn` — 平仓 / 强平日志

### 2.1 当前事务持锁路径

- `t_account` 行：通过 `getExistingAccountForUpdate` 取 SELECT FOR UPDATE
- `t_position` 行：通过 `position.close()` 内存修改 + `tradingPositionRepository.save` 的 UPDATE（无显式 SELECT FOR UPDATE，但有 status WHERE 子句作 CAS）
- 其他表均纯 INSERT，无行锁竞争

### 2.2 当前事务时长估算

每次 settlePositionExit ≈ 6-7 次 SQL（取决于强平 / SL-TP 是否触发）+ 1 次 outbox INSERT = 8-9 ms 持锁。

## 3. 设计

### 3.1 拆分原则

**强一致必须保留主事务（不能拆出）**：

- `t_account` + `t_ledger`：账户/账本数据真源，资金一致性核心
- `t_position`：持仓状态机真源，客户端 / 风控引擎读
- `t_outbox`：事件发布幂等保证；若 outbox 落盘失败，主事务回滚以保证"事件出现等于 DB 完成"

**最终一致可接受（可拆 outbox 异步）**：

- `t_trade`：成交历史展示用，用户查询通过 `findByUserIdPaginated`；延迟 < 5s 可接受
- `t_risk_exposure`：敞口监控用，已有 5 秒/Schedule 全表 SUM 兜底（`PlatformExposureGuardScheduler`）
- `t_liquidation_log`：强平审计，仅强平场景写入，volume 很低
- `t_pending_order_trigger` 级联撤：SL/TP 挂单已经触发 trigger reason，延迟撤掉不影响业务正确性（trigger 时仍会检查 parent position 状态）

### 3.2 拆分方案对比

| 方案 | 主事务保留 | outbox 异步消费 | 收益 | 风险 |
|---|---|---|---|---|
| **A 保守** | t_account / t_ledger / t_position / t_outbox / **t_trade / t_risk_exposure** | t_liquidation_log / t_pending_order_trigger 级联撤 | 持锁 -22%（9 SQL → 7 SQL） | 极低 — t_trade / t_risk_exposure 仍强一致 |
| **B 推荐** | t_account / t_ledger / t_position / t_outbox / **t_risk_exposure** | t_trade / t_liquidation_log / t_pending_order_trigger | 持锁 -33%（9 SQL → 6 SQL） | 中 — t_trade 异步审计：用户平仓后 < 5s 内查交易历史可能看不到本笔 |
| **C 激进** | t_account / t_ledger / t_position / t_outbox | t_trade / t_risk_exposure / t_liquidation_log / t_pending_order_trigger | 持锁 -44%（9 SQL → 5 SQL） | 高 — t_risk_exposure 异步：极端场景下下一次下单可能用到过期敞口 |

**本设计推荐 B 方案**：

- t_trade 异步可接受：用户体感 < 5s 延迟，平仓 API 返回的 PositionCloseResult 仍含 trade 对象（in-memory，可用于响应），后续查询走 DB
- t_risk_exposure **保留主事务**：风控引擎在下一笔下单时立即读取，异步可能让风控决策基于过期数据，影响监管合规
- t_liquidation_log + t_pending_order_trigger 拆出：审计/级联，无强实时需求

### 3.3 改动方向

#### 3.3.1 新增 outbox 事件类型

在 `falconx-trading-contract` 或现有 outbox event 集合中新增：

- `TradingPositionClosePostProcessEventPayload`：含 positionId + tradeId（in-memory 生成的 snowflake，主事务内 reserve）+ trade 关键字段（symbol/side/qty/price/realizedPnl）+ closeReason
- `TradingLiquidationLogPostProcessEventPayload`：仅强平场景含 liquidation_log 全字段
- `TradingPendingOrderCascadeCancelEventPayload`：含 positionId + closeReason

每个 outbox 行的 topic 可以是同一个 `falconx.trading.position.close.post-process` 或拆为 3 个 topic — 需在 spec 中决定。**本设计统一用 1 个 topic** `falconx.trading.position.close.post-process`，event_type 字段区分子类型，简化消费者订阅。

#### 3.3.2 主事务内仅保留

```java
@Transactional
public PositionCloseResult settlePositionExit(...) {
    // 1. 行情快照 / PnL 计算 / 账户取锁（同现状）
    BigDecimal effectiveMarkPrice = ...;
    BigDecimal realizedPnl = ...;
    TradingAccount settlementAccount = tradingAccountService.getExistingAccountForUpdate(...);

    // 2. 账户结算（t_account + t_ledger）—— 同现状
    PositionSettlementResult settlement = tradingAccountService.settlePositionExit(...);

    // 3. 持仓状态机（t_position）—— 同现状
    TradingPosition exitedPosition = tradingPositionRepository.save(position.close(...));

    // 4. 敞口更新（t_risk_exposure）—— 保留主事务（B 方案决策）
    tradingRiskObservabilityService.applyClosePosition(...);

    // 5. 提前 reserve trade 的雪花 id（不写 DB，仅生成 id 用于响应 + outbox payload）
    Long tradeId = idGenerator.nextId();

    // 6. outbox 主事件 + post-process 事件（t_outbox）
    tradingOutboxRepository.save(buildPositionClosedOrLiquidatedOutbox(...));
    tradingOutboxRepository.save(buildPostProcessTradeOutbox(tradeId, exitedPosition, ...));
    if (liquidation) {
        tradingOutboxRepository.save(buildPostProcessLiquidationLogOutbox(...));
    }
    if (hasSlTpChildren(position)) {
        tradingOutboxRepository.save(buildPendingOrderCascadeCancelOutbox(...));
    }

    // 7. 构造响应（in-memory trade 对象，含 tradeId，但 DB 此时未写）
    TradingTrade trade = buildInMemoryTrade(tradeId, exitedPosition, ...);

    // 8. afterCommit 注册：内存快照清理（同现状）
    registerSnapshotRemoval(exitedPosition, trade, settlement.account(), null);

    return new PositionCloseResult(exitedPosition, trade, settlement, null);
}
```

主事务从 9 SQL 降到 5-6 SQL（取决于强平 / SL-TP 是否触发 outbox 额外 INSERT）。

#### 3.3.3 新增 outbox 消费者

在 trading-core 内部新增 `TradingPositionClosePostProcessOutboxConsumer`（既是 Kafka producer 又是 consumer — outbox 发出去 Kafka 后由本服务自己消费），订阅 `falconx.trading.position.close.post-process` topic：

- `event_type=TRADE_WRITE`：从 payload 构造 `TradingTrade` 写 `t_trade`
- `event_type=LIQUIDATION_LOG_WRITE`：写 `t_liquidation_log`
- `event_type=PENDING_ORDER_CASCADE_CANCEL`：调 `pendingOrderRepository.cancelAllSlTpByPositionId`

每个消费者方法独立 `@Transactional`，失败由现有 outbox retry 机制兜底。

#### 3.3.4 t_trade.id 提前分配

为保证响应中的 `trade.tradeId` 与 DB 落盘后的 id 一致，主事务内提前 `idGenerator.nextId()` 生成 trade id（雪花），写入 outbox payload。消费者写 t_trade 时显式用此 id（不重新生成）。

### 3.4 一致性保证

- **outbox 已是 team 现有机制**（trading-core + market + wallet 都有 outbox），失败 retry + 状态机 PENDING/SENT/FAILED 完备
- **post-process 事件失败**：outbox `retry_count` 累加 + `next_retry_at` 推后；超过 max 进 FAILED 状态需人工介入
- **t_trade 异步延迟监控**：新增 prometheus metric `trading.position.close.trade.write.lag.seconds`（trade 写入与 position close 时间差）
- **客户端查交易历史的延迟体感**：API 文档需注明"成交记录最多 5 秒后可见"，或前端在 PositionCloseResult 返回时本地缓存 trade 对象 5 秒

### 3.5 调用方影响

| 调用 | 影响 |
|---|---|
| 手动平仓 API（`TradingPositionClosePlacementApplicationService.handleManualClose`） | `PositionCloseResult` 返回值不变，`trade` 仍是 in-memory 对象，前端可直接使用 |
| TP/SL 触发（`QuoteDrivenEngine.handleTpSlTrigger`） | 同上 |
| 强平触发（`QuoteDrivenEngine.handleLiquidation`） | 同上 |
| 管理端历史交易查询 | DB 读，与主事务异步消费速度相关；正常 < 5s 可见 |

### 3.6 兼容性

- **现有 outbox schema 不变**：`t_outbox` 已支持 event_type / payload 字段，无需 migration
- **PositionCloseResult 对外契约不变**：仍含 position + trade + account + liquidationLog
- **Kafka topic**：**新增** `falconx.trading.position.close.post-process`；这是 internal post-process，不算跨服务契约变更（仅 trading-core 自己发自己消费）
- **t_trade schema 不变**

## 4. 失败语义

| 失败点 | 行为 |
|---|---|
| 主事务内任一表 INSERT/UPDATE 失败 | 事务回滚；客户端收到错误响应；内存快照未删（不会幽灵） |
| outbox post-process 消费失败 | outbox retry；t_trade 延迟落盘但主事务已 commit，事件最终一致；监控 `trading.outbox.retry-count` |
| outbox 消费持续失败超过 max retry | 进入 `t_outbox.status=FAILED`，运维介入；可手工触发 `repair` |
| 强平场景 + t_liquidation_log 异步失败 | 主事务已 commit，强平资金已结算，仅审计日志延迟；运营可补录 |

**关键改进 vs 现状**：

- 现状："t_liquidation_log INSERT 失败 → 整事务回滚 → 用户余额未扣 → 但内存快照已经在 afterCommit 之前的 close() 调用中被清掉？" 这种"数据库存在、内存消失"的幽灵状态在 spec 中称为风险点
- 改后：内存快照清理仍在 `afterCommit`（与现状一致），但事务范围更小，幽灵状态发生概率大幅降低（5 张表 → 5 SQL 主事务，外加 4 表的 outbox 异步）

## 5. 测试矩阵

| 测试用例 | 期望 |
|---|---|
| 手动平仓 happy path | t_account/t_ledger/t_position/t_outbox 立即落盘；t_trade 在 < 5s 内出现；PositionCloseResult.trade 字段含正确 in-memory 对象 |
| 强平 happy path | 同上 + t_liquidation_log < 5s 内落盘 |
| SL/TP 触发 + 持仓有 SL/TP 子挂单 | 主事务无 t_pending_order_trigger UPDATE；< 5s 后子挂单状态变为 CANCELED |
| outbox post-process 消费失败 1 次 → 重试成功 | t_trade 在第 2 次消费后出现；outbox retry_count=1 |
| outbox 消费持续失败超 max retry | t_outbox.status=FAILED；告警；t_trade 始终未出现 |
| 主事务内 t_position UPDATE 失败 | 整事务回滚；outbox 无新行；t_account 无变更；内存快照未清 |
| 并发 close 同一 position（重复 API 请求） | 第一笔成功 → t_position.status=CLOSED；第二笔走 close-state-check 拒绝（与现状一致） |
| TradeId reserve 后 outbox 写入失败 | 整事务回滚；id 浪费一个雪花但无副作用（雪花 id 不连续是预期） |

## 6. 风险与回滚

### 6.1 风险

1. **t_trade 异步延迟超过 5s**：高峰时段或 Kafka backlog 可能让 t_trade 延迟到 30s+。监控指标 `trading.position.close.trade.write.lag.seconds`，超过 10s 报警。
2. **PositionCloseResult.trade 与 DB t_trade 不一致**：in-memory trade 与 outbox payload + 消费者写入必须严格对齐（同 id + 同 amount + 同 timestamp）。单测覆盖。
3. **outbox 消费者订阅初始化失败**：服务启动时如果 Kafka consumer 注册失败，post-process 不会跑。spec 要求消费者在 Spring Boot ready 阶段验证订阅成功，否则启动 fail-fast。
4. **强平时 t_liquidation_log 延迟**：监管合规可能要求强平日志实时记录。**Follow-up 决策**：是否强平场景下用 A 方案（保留 t_liquidation_log 主事务）+ 其他用 B 方案？
5. **Outbox 自消费的延迟**：trading-core 自己 produce 自己 consume，单实例无 partition 优势；多实例情况下 Kafka 分区随机分配可能跨节点消费。需要确认 partition strategy。

### 6.2 回滚

- 单 commit revert
- 不涉及 schema 变更（outbox 已有）
- 但**有数据迁移考虑**：revert 后未消费完的 post-process outbox 行会变成 orphan（消费者不存在），需要先停消费 / 等队列 drain / 再 revert。**实施时建议**：
  - revert PR 包含 1 个开关 `falconx.trading.position.close.async-post-process.enabled`（默认 true 启用异步，false 走旧的同步路径）
  - 部署后保留开关 1 周观察，确认稳定后才删除旧代码

## 7. R6 验证清单

- [ ] 单元测试覆盖 §5 测试矩阵全部用例
- [ ] 集成测试：跑 50 笔模拟手动平仓 + 10 笔强平 + 5 笔含 SL/TP 子挂单 → DB 比对 5s 后 t_trade / t_liquidation_log / t_pending_order_trigger 全部落盘
- [ ] 主事务持锁时长测量：`EXPLAIN ANALYZE` + 部署后采样 prometheus `mysql_trx_active_duration_seconds`
- [ ] Outbox post-process 消费 lag 测量：部署后 24h 内 `kafka_consumer_lag` peak < 100
- [ ] 部署 demo 跑 48h，对比 `Innodb_row_lock_waits` 增量

## 8. 涉及范围

| 文件 | 改动 |
|---|---|
| `falconx-trading-contract/src/main/java/com/falconx/trading/contract/event/TradingPositionClosePostProcessEventPayload.java`（新） | 新 outbox event payload record |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/application/TradingPositionCloseApplicationService.java` | settlePositionExit 拆分主事务 + outbox post-process |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/consumer/TradingPositionClosePostProcessOutboxConsumer.java`（新） | 异步消费 trade / liquidation_log / pending order cascade |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/repository/TradingTradeRepository.java` | 加 `saveWithExplicitId(TradingTrade)` 方法（消费者使用 outbox 中的 reserved id） |
| `falconx-trading-core-service/src/main/resources/application.yml` | 新 topic 配置 `falconx.trading.position.close.post-process` |
| `falconx-trading-core-service/src/main/resources/application.yml` | 加 `falconx.trading.position.close.async-post-process.enabled: true` 开关 |
| 新测试类 × 3-4 | 主事务、消费者、集成 |

## 9. 不做（YAGNI）

- ❌ 不动 t_trade / t_position / t_account schema
- ❌ 不引入 event sourcing 模式
- ❌ 不拆 t_risk_exposure 异步（实时风控敏感）
- ❌ 不变更 PositionCloseResult API 响应结构
- ❌ 不新增跨服务 Kafka 消费者（trading-core 自产自销）
- ❌ 不在本 spec 解决 Outbox 多分区/多消费实例的全局顺序保证（独立工单）

## 10. 待用户确认事项

1. **方案 A/B/C 决策**：本设计默认 B（t_trade 异步、t_risk_exposure 同步、t_liquidation_log + 级联撤异步）。如果监管或产品坚持 t_trade 同步，改回 A。
2. **强平场景特例**：是否需要 t_liquidation_log 在强平场景下保留主事务（即 A+B 混合）？— 监管合规视角
3. **开关 `async-post-process.enabled`**：是否需要保留这个 feature flag，方便快速回退？默认 true，但保留代码兼容性
4. **post-process topic 命名**：`falconx.trading.position.close.post-process` 还是 3 个独立 topic？

## 11. 后续

design approve 后 → 创建 implementation plan，文件结构：

- `2026-05-26-s6-trading-position-close-tx-split-plan.md`
- 任务依赖：新 outbox payload contract → 主事务拆分 → outbox 消费者实现 → feature flag 开关 → 单元测试 → 集成测试 → 部署节奏（**S6 必须等 C1 部署 48h 稳定后才能上**，参见 epic §6）
