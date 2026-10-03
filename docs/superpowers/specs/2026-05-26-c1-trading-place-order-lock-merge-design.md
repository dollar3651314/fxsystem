# C1 — trading 下单合并 SELECT FOR UPDATE 设计

- Sprint 3 第 1 项
- Epic：[`2026-05-26-sprint-3-epic-overview.md`](./2026-05-26-sprint-3-epic-overview.md)
- 性能报告引用：[`docs/perf/性能分析-2026-05-25.md`](../../perf/性能分析-2026-05-25.md) §2 P0、§10 修复路线图 TOP 5 第 4 项
- 状态：待审

## 1. 背景

**性能报告 §2 P0 原文**：

> trading 下单链路三次 `SELECT FOR UPDATE` 同账户行
> - 文件：`TradingOrderPlacementApplicationService.java:126`、`TradingAccountService.java:49`
> - 现象：`getOrCreateAccountForUpdate()` → `reserveMargin()` → `chargeFee()` → `confirmMarginUsed()` 同一事务内多次 SELECT+UPDATE 同一账户行
> - 影响：高 TPS 用户行锁竞争（运行时 10597 row_lock_waits 大概率来自这里）

**InnoDB 行为说明**：同事务内多次 SELECT FOR UPDATE 同一行只取 1 次锁（第二次开始连接已持锁，不阻塞）。但每次 method 都执行 `SELECT + UPDATE + INSERT ledger`，**持锁时间 = 3 次 SQL roundtrip ≈ 3-6ms**。并发用户竞争同一账户时，持锁时间直接决定吞吐。

**收益预期**：
- 持锁时间 3 SQL → 1 SQL roundtrip + batch INSERT
- 理论持锁时间下降 ~67%
- 对应 `Innodb_row_lock_waits` 5 天 10597 次 → 预期降到 ~3500 次

## 2. 当前实现细节

文件：`falconx-trading-core-service/src/main/java/com/falconx/trading/service/impl/DefaultTradingAccountService.java`

```
public TradingAccount reserveMargin(...) {
    TradingAccount before = getOrCreateAccountForUpdate(userId, currency);  // SELECT FOR UPDATE
    TradingAccount after = tradingAccountRepository.save(before.reserveMargin(margin, occurredAt));  // 领域对象内存计算 + UPDATE
    writeLedger(before, after, ORDER_MARGIN_RESERVED, ...);  // INSERT t_ledger
    return after;
}
// chargeFee / confirmMarginUsed 同模式
```

`TradingOrderPlacementApplicationService` 调用顺序：

```
1. getOrCreateAccountForUpdate(userId, currency)     ← SELECT FOR UPDATE（取锁）
2. 风控校验
3. reserveMargin(...)                                 ← 内含 SELECT FOR UPDATE + UPDATE + INSERT ledger
4. chargeFee(...)                                     ← 同上
5. confirmMarginUsed(...)                             ← 同上
```

3-5 步实际是同事务内已持锁的"4 次 SELECT FOR UPDATE 同一行 + 3 次 UPDATE + 3 次 INSERT ledger"。

## 3. 设计

### 3.1 新增复合方法

在 `TradingAccountService` 接口加：

```java
/**
 * 原子应用下单的资金变更：保证金预留 + 手续费扣减 + 保证金确认占用，写 3 条 ledger 流水。
 *
 * <p>专为 {@code TradingOrderPlacementApplicationService} 设计的复合方法，避免下单链路
 * 多次锁同一账户行（性能报告 §2 P0 C1 Sprint 3 改造）。
 *
 * <p>事务边界：必须在外层 @Transactional 内调用；调用前外层应已持有该账户的
 * SELECT FOR UPDATE 锁（通过 getOrCreateAccountForUpdate / getExistingAccountForUpdate）。
 *
 * <p>幂等性：与原 reserveMargin/chargeFee/confirmMarginUsed 保持一致 — 3 条 ledger
 * 分别使用 {@code clientOrderId + ":reserve" / ":fee" / ":confirm"} 作为 idempotency_key，
 * t_ledger 的 (user_id, idempotency_key) UNIQUE 约束保证重试幂等。
 *
 * @param existingAccount 已加锁的账户对象（外层 getOrCreateAccountForUpdate 返回）
 * @param margin 预留并占用的保证金
 * @param fee 扣减的手续费
 * @param clientOrderId 客户端订单 ID（用于派生 3 条 ledger 的 idempotency_key）
 * @param referenceNo 业务参考号（用于 ledger reference_no 字段，3 条共用）
 * @param occurredAt 发生时间
 * @return 变更后账户
 */
TradingAccount applyOrderPlacementAccountChange(
        TradingAccount existingAccount,
        BigDecimal margin,
        BigDecimal fee,
        String clientOrderId,
        String referenceNo,
        OffsetDateTime occurredAt);
```

**关键决策**：

- **接受 `existingAccount` 而不是 `userId+currency`**：复用外层已加锁的对象，避免重复 SELECT FOR UPDATE。
- **领域对象内存计算 3 步**：`before.reserveMargin(margin).chargeFee(fee).confirmMarginUsed(margin)` — 保持 DDD 行为不变，每步领域规则（如余额校验）仍生效。
- **单 UPDATE 写 3 字段**：通过 `tradingAccountRepository.save(after)`，after 是 3 步计算后的最终状态。
- **批量 INSERT 3 条 ledger**：新增 mapper `batchInsert(List<TradingLedgerEntry>)`，单 SQL 写 3 行。

### 3.2 实现伪代码

```java
public TradingAccount applyOrderPlacementAccountChange(
        TradingAccount existingAccount,
        BigDecimal margin,
        BigDecimal fee,
        String clientOrderId,
        String referenceNo,
        OffsetDateTime occurredAt) {
    // 领域对象 3 步链式计算（每步内部校验，失败抛业务异常）
    TradingAccount afterReserve = existingAccount.reserveMargin(margin, occurredAt);
    TradingAccount afterFee = afterReserve.chargeFee(fee, occurredAt);
    TradingAccount finalAccount = afterFee.confirmMarginUsed(margin, occurredAt);

    // 单 UPDATE 写最终状态（balance / frozen / margin_used 三字段）
    tradingAccountRepository.save(finalAccount);

    // 批量 INSERT 3 条 ledger（biz_type 保留原值，分别幂等键）
    List<TradingLedgerEntry> ledgers = List.of(
        buildLedger(existingAccount, afterReserve, ORDER_MARGIN_RESERVED,
                    margin, clientOrderId + ":reserve", referenceNo, occurredAt),
        buildLedger(afterReserve, afterFee, TRADE_FEE,
                    fee, clientOrderId + ":fee", referenceNo, occurredAt),
        buildLedger(afterFee, finalAccount, ORDER_MARGIN_USED_CONFIRM,
                    margin, clientOrderId + ":confirm", referenceNo, occurredAt)
    );
    tradingLedgerRepository.batchInsert(ledgers);

    return finalAccount;
}
```

### 3.3 调用方改动

`TradingOrderPlacementApplicationService` 4 步合并为 2 步：

```java
// 改前
TradingAccount account = tradingAccountService.getOrCreateAccountForUpdate(userId, currency);  // 取锁
TradingRiskDecision decision = tradingRiskService.evaluateMarketOrder(...);
account = tradingAccountService.reserveMargin(...);                                            // 3 次相同锁路径
account = tradingAccountService.chargeFee(...);
account = tradingAccountService.confirmMarginUsed(...);

// 改后
TradingAccount account = tradingAccountService.getOrCreateAccountForUpdate(userId, currency);  // 取锁
TradingRiskDecision decision = tradingRiskService.evaluateMarketOrder(...);
account = tradingAccountService.applyOrderPlacementAccountChange(
    account, decision.margin(), decision.fee(),
    command.clientOrderId(), referenceNo, now);
```

### 3.4 兼容性

- **保留原 3 方法**：`reserveMargin`/`chargeFee`/`confirmMarginUsed` 不删除，可能被其他路径使用（如 pending order trigger）。改动只影响 `TradingOrderPlacementApplicationService` 一处。
- **ledger schema 不变**：t_ledger 仍写 3 行，biz_type 不变（`ORDER_MARGIN_RESERVED`/`TRADE_FEE`/`ORDER_MARGIN_USED_CONFIRM`），idempotency_key 命名前缀不变。
- **Kafka outbox / 事件**：本路径不涉及，无影响。

## 4. 失败语义

| 失败点 | 行为 |
|---|---|
| 领域对象内存校验失败（如 `frozen < 0`） | 抛业务异常，事务回滚，外层 ApplicationService 转换为 API 错误码 |
| `tradingAccountRepository.save` UPDATE 失败（乐观锁版本号冲突或唯一约束） | 抛 DataAccessException，事务回滚 |
| `batchInsert` 任一 ledger 唯一键冲突（重复幂等键 — 重复下单） | 抛 DuplicateKeyException，事务回滚 |
| 部分 ledger INSERT 失败 | 单 SQL 批量插入是原子的，要么全成功要么全失败；事务回滚 |

**与原行为对比**：原 3 步分别在事务内顺序执行，任一失败也是整体事务回滚。**复合方法与原行为一致**。

## 5. 测试矩阵

新增测试（`DefaultTradingAccountServiceTests` 或新 `ApplyOrderPlacementAccountChangeTests`）：

| 测试用例 | 期望 |
|---|---|
| 正常路径：margin=100, fee=1，账户 balance=500 | balance=499, frozen=0, margin_used=100；3 条 ledger biz_type 分别正确 |
| 余额不足（balance < margin+fee） | 领域对象校验抛 `InsufficientBalanceException`，无 t_account / t_ledger 变更 |
| 重复下单（同 clientOrderId）触发 ledger UNIQUE 冲突 | 抛 DuplicateKeyException，整事务回滚（账户也不变） |
| 边界值（margin=0，fee=0） | 行为按现有领域规则——若 `TradingAccount.reserveMargin(0)` 校验通过则写 3 条 amount=0 ledger，否则抛异常；实现时与单独调 `reserveMargin(0)` 做行为对照 |
| `existingAccount` 来自不同用户 | （此为 API 误用，但应有断言）抛 IllegalArgumentException |

回归测试覆盖：现有 `TradingOrderPlacementApplicationService` 的集成测试不应感知变化（黑盒行为不变）。

## 6. 风险与回滚

### 6.1 风险

1. **ledger biz_type 行为漂移**：3 条 ledger 的金额 / before/after 字段计算逻辑必须与原 reserveMargin/chargeFee/confirmMarginUsed 完全一致。**风险点**：DDD 领域对象 3 步链式计算的中间状态作为 ledger 行的 `balance_before/balance_after`，与原"每方法独立 SELECT 后计算"的中间值是否相同？**需在单测中显式覆盖每步 before/after**。

2. **pending order trigger 等其他路径**：原 reserveMargin/chargeFee/confirmMarginUsed 可能被 pending order trigger 或其他业务路径使用。本改动只动 `TradingOrderPlacementApplicationService`，但需要确认 grep 结果无遗漏。

3. **乐观锁版本号（已确认非问题）**：t_account schema 有 `version INT NOT NULL DEFAULT 0` 字段，但 `TradingAccountMapper.xml` 的 `updateTradingAccount` SQL **既不 SET version+1 也不 WHERE version = #{version}**——当前系统仅依赖 SELECT FOR UPDATE 悲观锁保证一致性，乐观锁未启用。复合方法保持同款 SQL 模式（单 UPDATE 三字段，不动 version），不引入新的并发模型。**Follow-up**：未来若启用乐观锁需要全局统一改 SQL，与本 spec 解耦。

### 6.2 回滚

- 单 commit revert
- 不涉及 schema 变更 / Kafka payload / 状态机
- 部署后若发现 ledger 计算异常，立即 revert + R6 重测

## 7. R6 验证清单（实现时执行）

实现后必须验证：

- [ ] 单元测试矩阵全部通过
- [ ] 现有 `TradingOrderPlacementApplicationService` 集成测试（含正常下单、余额不足、重复下单）全部通过
- [ ] 手动跑一笔下单 → DB 比对 t_account / t_ledger 与原行为完全一致（balance/frozen/margin_used 数值 + 3 条 ledger 的 biz_type/amount/balance_before/balance_after/idempotency_key 全字段）
- [ ] `EXPLAIN ANALYZE` 比对原 + 新版 SQL 实际耗时
- [ ] 部署 demo 服务器，跑 50 笔模拟下单观察 `Innodb_row_lock_waits` 增量是否下降

## 8. 涉及范围

| 文件 | 改动 |
|---|---|
| `falconx-trading-core-service/src/main/java/com/falconx/trading/service/TradingAccountService.java` | 接口加 `applyOrderPlacementAccountChange` |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/service/impl/DefaultTradingAccountService.java` | 实现新方法 + `buildLedger` 私有方法 |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/repository/TradingLedgerRepository.java` | 接口加 `batchInsert(List<TradingLedgerEntry>)` |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/repository/MybatisTradingLedgerRepository.java` | 实现 batchInsert |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/repository/mapper/TradingLedgerMapper.java` | 加 `int batchInsert(@Param("list") List<TradingLedgerRecord>)` |
| `falconx-trading-core-service/src/main/resources/mapper/trading/TradingLedgerMapper.xml` | 加批量 INSERT SQL |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/application/TradingOrderPlacementApplicationService.java` | 调用方改 2 步 |
| 新测试类 | `ApplyOrderPlacementAccountChangeTests` |

## 9. 不做（YAGNI）

- ❌ 不合并 `getOrCreateAccountForUpdate` 到复合方法（外层风控需要 account 对象，不能耦合）
- ❌ 不删除原 reserveMargin/chargeFee/confirmMarginUsed（其他路径可能依赖）
- ❌ 不动 t_account schema（balance/frozen/margin_used 三字段已是分开的，无需 generated column）
- ❌ 不引入 stored procedure（保持 ORM 链路一致）

## 10. 后续

design approve 后 → 创建 implementation plan（`2026-05-26-c1-trading-place-order-lock-merge-plan.md`），含：

- 文件粒度的改动 patch 草稿
- 测试用例代码草稿
- mvn -pl trading-core test 通过判定
- 部署节奏（与 S6 解耦，先单独上 demo 观察 2 天）
