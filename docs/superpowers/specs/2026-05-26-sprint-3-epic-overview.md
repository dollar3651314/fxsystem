# Sprint 3 Epic — 三项高风险 P0 性能/架构整改

- 日期：2026-05-26
- 范围：3 项性能分析报告（[`docs/perf/性能分析-2026-05-25.md`](../../perf/性能分析-2026-05-25.md)）§0.5/§0.6/§0.7 标记 "延后到 Sprint 3" 的高风险 P0
- 本会话交付：**仅设计文档**（3 份 design spec + 3 份 implementation plan）；代码实现 + 部署 + R6 回归留给后续独立会话/team 质量会
- 决策：3 项串行，按 **C1 → S6 → S5** 顺序

## 1. 背景

[`docs/perf/性能分析-2026-05-25.md`](../../perf/性能分析-2026-05-25.md) §0.5/§0.6/§0.7 完成 Sprint 1+2 后，13 项 P0 中 10 项完全修复，3 项延后到本 Sprint 3：

| 编号 | 标题 | 性能报告位置 |
|---|---|---|
| **C1** | trading 下单合并三次 SELECT FOR UPDATE 同账户行 | §2 P0 |
| **S6** | TradingPositionCloseApplicationService 持仓平仓事务跨 6 表 → outbox 拆分 | §6 P0 |
| **S5** | MarketDataIngestionApplicationService 同步 6+ 下游 → 异步化 | §4 P0 |

三项共同特征：**账本审计 / 核心数据通路敏感**，错了影响所有交易或所有下游消费者，必须严格走 R2 contract designer + R6 回归。

## 2. 不目标（本 Epic 不做）

- ❌ 本会话不写实现代码
- ❌ 不重新设计账本流水 biz_type 编码（保持现有 ledger 类型语义）
- ❌ 不变更 Kafka topic / payload / 状态机契约（C1/S5/S6 都属内部实现优化）
- ❌ 不引入新的中间件（不上 MQ broker / event sourcing）
- ❌ 不改 demo 服务器（Sprint 3 实现 + 部署留给后续会话）

## 3. 三项关系与依赖

```
        ┌──── C1（trading order placement）─────┐
        │   reserveMargin/chargeFee/confirm     │
        │   合并为单 UPDATE + 批量 ledger        │
        │   独立，先做                            │
        └─────────────────────────────────────────┘

        ┌──── S6（trading position close）─────┐
        │   settlePositionExit 6 表事务         │
        │   → 账户+持仓+outbox 原子              │
        │   → trade/敞口/清算日志 outbox 异步落盘 │
        │   与 C1 无依赖                          │
        └─────────────────────────────────────────┘

        ┌──── S5（market ingestion）───────────┐
        │   ingestPlatformQuote 同步 6+ 下游     │
        │   → 同步保留 quote cache write + kline │
        │   → 异步化 Kafka publish + ClickHouse  │
        │   独立通路，最后做                       │
        └─────────────────────────────────────────┘
```

**三项之间无代码依赖**，但 C1/S6 影响 trading-core 内部，建议先做完单独部署验证；S5 影响 market-service，可与 trading 部署解耦。

## 4. 每项简要

### 4.1 C1 — trading 下单合并 SELECT FOR UPDATE

**现状**：
- `TradingOrderPlacementApplicationService` 顺序调 4 次：`getOrCreateAccountForUpdate` + `reserveMargin` + `chargeFee` + `confirmMarginUsed`
- InnoDB 同事务多次 SELECT FOR UPDATE 同行只锁 1 次，但每次方法内部都做 `SELECT + UPDATE + INSERT ledger`，整体持锁时间 = 3 次 SQL roundtrip
- ledger 写入 3 条独立行：`MARGIN_RESERVE` + `TRADE_FEE` + `MARGIN_USED_CONFIRM`

**改动方向**：
- `TradingAccountService` 加 `reserveMarginChargeFeeAndConfirmMarginUsed(userId, currency, margin, fee, idempotencyPrefix, referenceNo, occurredAt)` 复合方法
- 单 UPDATE 同时改 `balance/frozen/marginUsed` 三字段
- 批量 INSERT 3 条 ledger 流水（biz_type 保持原值，分别幂等键 `:reserve` / `:fee` / `:confirm`）
- 调用方 `TradingOrderPlacementApplicationService` 从 4 步 → 2 步（`getOrCreateAccountForUpdate` + 复合方法）

**风险**：
- ledger 三流水的 biz_type / idempotency 保持，不破坏审计追溯
- 复合方法必须保持事务边界（不能拆 outer/inner transaction）

**回滚点**：单 commit，git revert 即可

### 4.2 S6 — 持仓平仓事务拆分

**现状**：
- `TradingPositionCloseApplicationService.settlePositionExit` 单事务跨 6 张表：账户结算 + 持仓更新 + trade 写 + 敞口更新 + 清算日志 + outbox
- 任一表写失败整事务回滚，但内存快照已变 → 持仓"DB 在 / 内存消失"幽灵状态
- 强平场景下持锁过长，引擎暂停其他持仓的 tick 处理

**改动方向**：
- 事务内只保留 **必须原子** 的：账户 + 持仓 + outbox 三条
- `trade` 表写入 + 敞口更新 + 清算日志拆到 outbox，异步消费
- 内存快照变更放在 `afterCommit`（与 [S1] 同模式）

**风险**：
- outbox 消费失败时 trade/敞口/清算数据滞后，需要明确"最终一致"窗口（建议 < 5s）
- 强平 + 手动平仓共用此方法，行为不能漂移

**回滚点**：单 commit，需要明确 outbox 事件版本号策略（避免新旧并存）

### 4.3 S5 — market ingestion 异步化

**现状**：
- `MarketDataIngestionApplicationService.ingestPlatformQuote` 每 tick 顺序调 6+ 下游：
  1. Redis quote cache write（`RedisMarketReferenceQuoteRepository.saveLastValid`）
  2. ClickHouse quote tick write（队列）
  3. Kafka publish `market.price.tick`
  4. Kafka publish `market.quote.updated`
  5. Kline 聚合计算（每条 tick × 6 interval）
  6. 三次 Kafka publish `market.kline.update`

**改动方向**：
- **同步保留**：Redis 写（trading-core 下单时同读）+ Kline 聚合（数据依赖链）
- **异步化**：Kafka publish（Producer 已有 buffer，移到内部 executor）+ ClickHouse write（已经是入队，确认非阻塞）
- 引入轻量 `quoteDownstreamDispatcher`（背压 + 顺序保证按 symbol 分片）

**风险**：
- 同 symbol quote 顺序必须保证（按 symbol 分片到独立 executor 即可）
- Kafka backpressure 时不能丢消息（异步 send + 失败 outbox 兜底）
- ClickHouse 已经是批量 flush（10s / 200 batch），异步化收益有限，重点是 Kafka

**回滚点**：拆 2-3 个 commit（先做 Kafka 异步，再做整体编排）

## 5. 执行节奏

```
本会话（2026-05-26）：
├── ✅ Sprint 3 epic overview spec（本文档）
├── ⏳ C1 design spec（下一步）
├── ⏳ C1 implementation plan（design 通过后）
├── ⏳ S6 design spec
├── ⏳ S6 implementation plan
├── ⏳ S5 design spec
└── ⏳ S5 implementation plan

下个会话或 team 质量会：
├── C1 R6 回归测试 + 实现 + 部署 + 监控
├── S6 同上
└── S5 同上
```

## 6. 跨 Sprint 风险检查

- **C1 + S6 都改 trading-core**：两个改动同时部署会让回归扇出更大，建议 C1 单独先上 + 观察 2 天，再上 S6
- **S5 改 market-service**：独立服务，可与 trading 部署解耦
- **ledger 审计回归**：C1 + S6 都涉及账本写入，需要专项 R6 测试覆盖 `INSERT t_ledger` 的 biz_type / amount / idempotency_key 是否符合原行为
- **强平场景**：S6 必须覆盖手动平仓 + TP + SL + 强平四种触发路径

## 7. 完成判定（DoD）

本会话 DoD：

- [x] Sprint 3 epic overview spec 已 commit
- [ ] C1 design spec 已 commit + 用户 approve
- [ ] C1 implementation plan 已 commit
- [ ] S6 design spec 已 commit + 用户 approve
- [ ] S6 implementation plan 已 commit
- [ ] S5 design spec 已 commit + 用户 approve
- [ ] S5 implementation plan 已 commit
- [ ] 6 个文档全部 push 到 main

后续 Sprint 实现 DoD（在每个 implementation plan 中具体化）：
- 代码改动 commit + push
- 单元测试覆盖账本/事务/数据流的关键不变量
- R6 集成测试 / E2E 回归通过
- 部署 demo 服务器 + 稳态 24h 观测无指标退化

## 8. 待用户确认事项

1. **C1 复合方法签名**：`reserveMarginChargeFeeAndConfirmMarginUsed(...)` 是否可接受？或者用更通用的 `applyOrderPlacementAccountChange(orderId, margin, fee)` 名字？— 留到 C1 design spec 讨论
2. **S6 outbox 消费方**：清算/敞口/trade 表的 outbox 异步消费者放在 trading-core 自己（同服务异步）还是 trading-core 内嵌 scheduler？— 留到 S6 design spec
3. **S5 同步/异步切分阈值**：哪些操作"必须同步"，哪些"可异步"？— 留到 S5 design spec

下一步：进入 **C1 design spec** 的具体设计问题。
