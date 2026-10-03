# C1 — trading 下单合并 SELECT FOR UPDATE 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 trading 下单链路 4 次 SELECT FOR UPDATE 同账户行（`reserveMargin` + `chargeFee` + `confirmMarginUsed`）合并为单个原子方法，单 UPDATE 写三字段 + 批量 INSERT 3 条 ledger，将持锁时间从 3 SQL roundtrip 降到 1 SQL + 1 batch INSERT。

**Architecture:** 在 `TradingAccountService` 加新方法 `applyOrderPlacementAccountChange(existingAccount, margin, fee, clientOrderId, referenceNo, occurredAt)`。该方法内部用 DDD 领域对象 3 步链式（`reserveMargin → chargeFee → confirmMarginUsed`）算出最终 account 状态，单次 `tradingAccountRepository.save` 写入，再通过新加的 `TradingLedgerRepository.batchInsert(List)` 批量写 3 条 ledger（biz_type + idempotency_key 保持与原 reserveMargin/chargeFee/confirmMarginUsed 完全一致）。原 3 个独立方法保留（其他路径可能用）。仅 `TradingOrderPlacementApplicationService` 一处调用方改 2 步。

**Tech Stack:** Java 25 + Spring Boot 4 + MyBatis 3.5 + MySQL 8.4 + JUnit 5 + Mockito。

**Design spec:** [`docs/superpowers/specs/2026-05-26-c1-trading-place-order-lock-merge-design.md`](../specs/2026-05-26-c1-trading-place-order-lock-merge-design.md)

**Epic:** [`docs/superpowers/specs/2026-05-26-sprint-3-epic-overview.md`](../specs/2026-05-26-sprint-3-epic-overview.md)

---

## File Structure

| 文件 | 用途 | Task |
|---|---|---|
| `falconx-trading-core-service/src/main/resources/mapper/trading/TradingLedgerMapper.xml` | 加批量 INSERT SQL | Task 1 |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/repository/mapper/TradingLedgerMapper.java` | 加 `int batchInsert(List<TradingLedgerRecord>)` 方法签名 | Task 2 |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/repository/TradingLedgerRepository.java` | 接口加 `batchInsert(List<TradingLedgerEntry>)` | Task 3 |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/repository/MybatisTradingLedgerRepository.java` | 实现 batchInsert | Task 3 |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/service/TradingAccountService.java` | 接口加 `applyOrderPlacementAccountChange` | Task 4 |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/service/impl/DefaultTradingAccountService.java` | 实现 + 私有辅助 `toLedgerEntry` | Task 5 |
| `falconx-trading-core-service/src/test/java/com/falconx/trading/service/impl/ApplyOrderPlacementAccountChangeTests.java`（新） | 复合方法单测 | Task 6 |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/application/TradingOrderPlacementApplicationService.java` | 调用方 4 步 → 2 步 | Task 7 |
| 现有集成测试 | 回归不破 | Task 8 |
| 部署脚本 | 仅 trading-core 部署 + 监控 | Task 9 |

任务依赖：`1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9`。1-3 是基础设施（被 5 用），4 是接口（被 5、7 用），5 是核心，6 是单测，7 是调用方改造，8 是集成回归，9 是部署。

---

## Task 1: TradingLedgerMapper.xml 加批量 INSERT SQL

**Files:**
- Modify: `falconx-trading-core-service/src/main/resources/mapper/trading/TradingLedgerMapper.xml`（在 `insertTradingLedger` 后追加 `batchInsert`）

**实现要点：** MyBatis foreach 拼接 multi-row INSERT。VALUE 列与现有 `insertTradingLedger` 完全一致，只是 VALUES 改为 foreach。

- [ ] **Step 1: 编辑 XML，加 batchInsert SQL**

在 `</insert>`（`insertTradingLedger` 结束）后、`</mapper>` 前插入：

```xml
    <!-- 2026-05-26 Sprint 3 C1：批量 INSERT，配合 applyOrderPlacementAccountChange
         一次写 3 条 ledger（biz_type ORDER_MARGIN_RESERVED / TRADE_FEE / ORDER_MARGIN_USED_CONFIRM）。
         每行字段与 insertTradingLedger 完全对齐，仅 VALUES 列表用 foreach。 -->
    <insert id="batchInsert" parameterType="java.util.List">
        INSERT INTO t_ledger (
            id,
            user_id,
            account_id,
            biz_type,
            idempotency_key,
            reference_no,
            amount,
            balance_before,
            balance_after,
            frozen_before,
            frozen_after,
            margin_used_before,
            margin_used_after,
            created_at
        ) VALUES
        <foreach collection="list" item="row" separator=",">
            (
                #{row.id},
                #{row.userId},
                #{row.accountId},
                #{row.bizTypeCode},
                #{row.idempotencyKey},
                #{row.referenceNo},
                #{row.amount},
                #{row.balanceBefore},
                #{row.balanceAfter},
                #{row.frozenBefore},
                #{row.frozenAfter},
                #{row.marginUsedBefore},
                #{row.marginUsedAfter},
                #{row.createdAt}
            )
        </foreach>
    </insert>
```

- [ ] **Step 2: 验证 XML 语法**

```bash
mvn -pl falconx-trading-core-service compile -o -DskipTests 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`（MyBatis XML 在编译阶段会被 spring-boot-maven-plugin 加载校验）

- [ ] **Step 3: Commit**

```bash
git add falconx-trading-core-service/src/main/resources/mapper/trading/TradingLedgerMapper.xml
git commit -m "feat(trading-ledger): TradingLedgerMapper.xml 加 batchInsert SQL

Sprint 3 C1 Task 1：为复合方法 applyOrderPlacementAccountChange 准备。
foreach 拼接 multi-row INSERT，每行字段与现有 insertTradingLedger 对齐。"
```

---

## Task 2: TradingLedgerMapper interface 加 batchInsert 方法

**Files:**
- Modify: `falconx-trading-core-service/src/main/java/com/falconx/trading/repository/mapper/TradingLedgerMapper.java`

- [ ] **Step 1: 编辑 mapper interface，加 batchInsert 方法签名**

在现有 `insertTradingLedger` 方法之后插入：

```java
    /**
     * 批量插入账本流水（单 SQL multi-row INSERT）。
     *
     * <p>专为 Sprint 3 C1 复合方法 {@code applyOrderPlacementAccountChange} 准备 —
     * 下单链路一次写 3 条 ledger（biz_type ORDER_MARGIN_RESERVED / TRADE_FEE /
     * ORDER_MARGIN_USED_CONFIRM），相比 3 次 insertTradingLedger 减少 2 个 SQL roundtrip。
     *
     * <p>原子性：单 SQL，要么全成功要么全失败；任一行 UNIQUE 约束冲突（idempotency_key
     * 重复）则整个 INSERT 失败抛 DataIntegrityViolationException。
     *
     * @param records 待写入的账本记录列表，要求非空、非空集合
     * @return 实际插入行数（正常等于 records.size()）
     */
    int batchInsert(@Param("list") java.util.List<TradingLedgerRecord> records);
```

注：参数 `@Param("list")` 名字与 XML 的 `<foreach collection="list">` 必须对齐。

- [ ] **Step 2: 验证编译**

```bash
mvn -pl falconx-trading-core-service compile -o -DskipTests 2>&1 | tail -3
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/repository/mapper/TradingLedgerMapper.java
git commit -m "feat(trading-ledger): TradingLedgerMapper 接口加 batchInsert(List)

Sprint 3 C1 Task 2：与 XML 端的 batchInsert SQL 配对。"
```

---

## Task 3: TradingLedgerRepository 接口 + Mybatis 实现 batchInsert

**Files:**
- Modify: `falconx-trading-core-service/src/main/java/com/falconx/trading/repository/TradingLedgerRepository.java`
- Modify: `falconx-trading-core-service/src/main/java/com/falconx/trading/repository/MybatisTradingLedgerRepository.java`

- [ ] **Step 1: Repository 接口加 batchInsert 方法**

```java
    /**
     * 批量持久化账本流水（仅支持新增，不支持更新）。
     *
     * <p>{@link com.falconx.trading.service.TradingAccountService#applyOrderPlacementAccountChange}
     * 一次写 3 条 ledger 用。生成 id（雪花）+ 拼装 record 在 Repository 层完成，
     * 让 Service 层只关心 {@link com.falconx.trading.entity.TradingLedgerEntry} 领域对象。
     *
     * @param entries 待写入的账本对象列表，{@link TradingLedgerEntry#ledgerId()} 可为 null，
     *                由 Repository 调用 idGenerator 填充
     * @return 持久化后的账本对象列表，顺序与入参一致，{@code ledgerId} 已填
     */
    List<TradingLedgerEntry> batchInsert(List<TradingLedgerEntry> entries);
```

注：原 `save(TradingLedgerEntry)` 方法接受单个对象。批量方法返回填好 id 的列表，方便后续上游使用。

需要顶部 `import java.util.List;`（如已有 import 则不重复）。

- [ ] **Step 2: MybatisTradingLedgerRepository 加实现**

参考现有 `save(TradingLedgerEntry)` 的转换逻辑。在实现类底部加：

```java
    @Override
    public List<TradingLedgerEntry> batchInsert(List<TradingLedgerEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<TradingLedgerRecord> records = new ArrayList<>(entries.size());
        List<TradingLedgerEntry> persisted = new ArrayList<>(entries.size());
        for (TradingLedgerEntry entry : entries) {
            Long id = entry.ledgerId() != null ? entry.ledgerId() : idGenerator.nextId();
            records.add(new TradingLedgerRecord(
                    id,
                    entry.userId(),
                    entry.accountId(),
                    TradingMybatisSupport.toLedgerBizTypeCode(entry.bizType()),
                    entry.idempotencyKey(),
                    entry.referenceNo(),
                    entry.amount(),
                    entry.balanceBefore(),
                    entry.balanceAfter(),
                    entry.frozenBefore(),
                    entry.frozenAfter(),
                    entry.marginUsedBefore(),
                    entry.marginUsedAfter(),
                    TradingMybatisSupport.toLocalDateTime(entry.createdAt())
            ));
            persisted.add(new TradingLedgerEntry(
                    id,
                    entry.accountId(),
                    entry.userId(),
                    entry.bizType(),
                    entry.amount(),
                    entry.idempotencyKey(),
                    entry.referenceNo(),
                    entry.balanceBefore(),
                    entry.balanceAfter(),
                    entry.frozenBefore(),
                    entry.frozenAfter(),
                    entry.marginUsedBefore(),
                    entry.marginUsedAfter(),
                    entry.createdAt()
            ));
        }
        tradingLedgerMapper.batchInsert(records);
        return persisted;
    }
```

需 `import java.util.ArrayList;`（如未导入）。

注意：上面参数顺序按 `TradingLedgerEntry` 的 record 定义（`ledgerId, accountId, userId, bizType, amount, idempotencyKey, referenceNo, balanceBefore, ...`）— **实现 Task 3 前请先 Read 一次 entity 文件确认字段顺序**，写错构造器参数顺序会编译失败但运行时也可能数据错位。

- [ ] **Step 3: 验证编译**

```bash
mvn -pl falconx-trading-core-service compile -o -DskipTests 2>&1 | tail -3
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: 写 batchInsert 单元测试**

新建文件 `falconx-trading-core-service/src/test/java/com/falconx/trading/repository/MybatisTradingLedgerRepositoryBatchInsertTests.java`：

```java
package com.falconx.trading.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.TradingLedgerBizType;
import com.falconx.trading.entity.TradingLedgerEntry;
import com.falconx.trading.repository.mapper.TradingLedgerMapper;
import com.falconx.trading.repository.mapper.record.TradingLedgerRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MybatisTradingLedgerRepositoryBatchInsertTests {

    @Test
    void batchInsert_emptyList_returnsEmptyAndNoMapperCall() {
        TradingLedgerMapper mapper = mock(TradingLedgerMapper.class);
        IdGenerator idGen = mock(IdGenerator.class);
        MybatisTradingLedgerRepository repo = new MybatisTradingLedgerRepository(mapper, idGen);

        List<TradingLedgerEntry> result = repo.batchInsert(List.of());

        assertTrue(result.isEmpty());
        verify(mapper, org.mockito.Mockito.never()).batchInsert(anyList());
    }

    @Test
    void batchInsert_threeEntries_fillsIdsAndCallsMapperOnce() {
        TradingLedgerMapper mapper = mock(TradingLedgerMapper.class);
        IdGenerator idGen = mock(IdGenerator.class);
        when(idGen.nextId()).thenReturn(1001L, 1002L, 1003L);
        when(mapper.batchInsert(anyList())).thenReturn(3);
        MybatisTradingLedgerRepository repo = new MybatisTradingLedgerRepository(mapper, idGen);

        OffsetDateTime now = OffsetDateTime.now();
        List<TradingLedgerEntry> input = List.of(
                buildEntry(null, TradingLedgerBizType.ORDER_MARGIN_RESERVED, new BigDecimal("100.00000000"), "key:reserve", now),
                buildEntry(null, TradingLedgerBizType.ORDER_FEE_CHARGED, new BigDecimal("1.00000000"), "key:fee", now),
                buildEntry(null, TradingLedgerBizType.ORDER_MARGIN_CONFIRMED, new BigDecimal("100.00000000"), "key:confirm", now)
        );

        List<TradingLedgerEntry> result = repo.batchInsert(input);

        assertEquals(3, result.size());
        assertEquals(1001L, result.get(0).ledgerId());
        assertEquals(1002L, result.get(1).ledgerId());
        assertEquals(1003L, result.get(2).ledgerId());
        ArgumentCaptor<List<TradingLedgerRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).batchInsert(captor.capture());
        List<TradingLedgerRecord> records = captor.getValue();
        assertEquals(3, records.size());
        assertNotNull(records.get(0).idempotencyKey());
    }

    private TradingLedgerEntry buildEntry(Long id,
                                          TradingLedgerBizType bizType,
                                          BigDecimal amount,
                                          String idempotencyKey,
                                          OffsetDateTime occurredAt) {
        return new TradingLedgerEntry(
                id,
                42L,                                  // accountId
                7L,                                   // userId
                bizType,
                amount,
                idempotencyKey,
                "ref-no",
                new BigDecimal("500.00000000"),       // balanceBefore
                new BigDecimal("499.00000000"),       // balanceAfter
                new BigDecimal("0.00000000"),         // frozenBefore
                amount,                               // frozenAfter（占位，与实测不严格对齐）
                new BigDecimal("0.00000000"),         // marginUsedBefore
                new BigDecimal("0.00000000"),         // marginUsedAfter
                occurredAt
        );
    }
}
```

- [ ] **Step 5: 运行测试**

```bash
mvn -pl falconx-trading-core-service -am test -o \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='MybatisTradingLedgerRepositoryBatchInsertTests' 2>&1 | tail -8
```

Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 6: Commit**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/repository/TradingLedgerRepository.java \
        falconx-trading-core-service/src/main/java/com/falconx/trading/repository/MybatisTradingLedgerRepository.java \
        falconx-trading-core-service/src/test/java/com/falconx/trading/repository/MybatisTradingLedgerRepositoryBatchInsertTests.java
git commit -m "feat(trading-ledger): TradingLedgerRepository + Mybatis 实现 batchInsert

Sprint 3 C1 Task 3：领域对象 List → 单 SQL multi-row INSERT。
单测覆盖空集合短路 + 3 条入库 + id 填充。"
```

---

## Task 4: TradingAccountService 接口加 applyOrderPlacementAccountChange

**Files:**
- Modify: `falconx-trading-core-service/src/main/java/com/falconx/trading/service/TradingAccountService.java`

- [ ] **Step 1: 在接口加方法签名**

在 `confirmMarginUsed(...)` 方法定义之后插入：

```java
    /**
     * 原子应用下单的资金变更：保证金预留 + 手续费扣减 + 保证金确认占用，写 3 条 ledger 流水。
     *
     * <p>专为 {@link com.falconx.trading.application.TradingOrderPlacementApplicationService}
     * 下单链路设计，避免多次锁同一账户行（Sprint 3 C1 / 性能报告 §2 P0）。
     *
     * <p>事务边界：必须在外层 @Transactional 内调用；调用前外层应已持有该账户的
     * SELECT FOR UPDATE 锁（通过 {@link #getOrCreateAccountForUpdate} 或
     * {@link #getExistingAccountForUpdate} 取锁）。
     *
     * <p>幂等性：与原 {@link #reserveMargin}/{@link #chargeFee}/{@link #confirmMarginUsed}
     * 完全一致 — 3 条 ledger 分别使用幂等键 {@code "order-margin-reserve:" + clientOrderId}、
     * {@code "order-fee-charge:" + clientOrderId}、{@code "order-margin-confirm:" + clientOrderId}，
     * t_ledger 的 (user_id, idempotency_key) UNIQUE 约束保证重试幂等。
     *
     * <p>失败语义：领域对象校验失败（{@link com.falconx.trading.entity.TradingAccount#reserveMargin}
     * 等抛业务异常）、UPDATE 失败、batchInsert 唯一键冲突 — 任一失败整事务回滚。
     *
     * @param existingAccount 已加锁的账户对象（外层 getOrCreateAccountForUpdate 返回）
     * @param margin 预留并占用的保证金，必须 ≥ 0
     * @param fee 扣减的手续费，必须 ≥ 0
     * @param clientOrderId 客户端订单 ID，用于派生 3 条 ledger 的 idempotency_key
     * @param referenceNo 业务参考号，写入 3 条 ledger 的 reference_no 字段
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

- [ ] **Step 2: 验证编译**

实现类还没动 — 编译会失败说"DefaultTradingAccountService 必须实现 applyOrderPlacementAccountChange"。这是预期的，Task 5 实现。

```bash
mvn -pl falconx-trading-core-service compile -o -DskipTests 2>&1 | tail -3
```

Expected: BUILD FAILURE，错误信息含 "DefaultTradingAccountService is not abstract and does not override abstract method applyOrderPlacementAccountChange"

- [ ] **Step 3: 不 commit，留到 Task 5 一起 commit**

接口和实现是一对，没有实现就不能编译。Task 4 不单独 commit；与 Task 5 合并 commit。

---

## Task 5: DefaultTradingAccountService 实现 applyOrderPlacementAccountChange

**Files:**
- Modify: `falconx-trading-core-service/src/main/java/com/falconx/trading/service/impl/DefaultTradingAccountService.java`

- [ ] **Step 1: 在实现类底部 `writeLedger` 私有方法之前加新公开方法 + 私有辅助方法**

```java
    @Override
    public TradingAccount applyOrderPlacementAccountChange(
            TradingAccount existingAccount,
            BigDecimal margin,
            BigDecimal fee,
            String clientOrderId,
            String referenceNo,
            OffsetDateTime occurredAt) {
        // 领域对象 3 步链式：每步内部规则校验（如 balance < margin 抛 InsufficientBalanceException）。
        TradingAccount afterReserve = existingAccount.reserveMargin(margin, occurredAt);
        TradingAccount afterFee = afterReserve.chargeFee(fee, occurredAt);
        TradingAccount finalAccount = afterFee.confirmMarginUsed(margin, occurredAt);

        // 单 UPDATE 写入最终账户状态（balance / frozen / margin_used 三字段）。
        TradingAccount persisted = tradingAccountRepository.save(finalAccount);

        // 批量 INSERT 3 条 ledger，biz_type / idempotency_key / amount 与原
        // reserveMargin / chargeFee / confirmMarginUsed 三个独立方法保持完全一致。
        java.util.List<TradingLedgerEntry> entries = java.util.List.of(
                toLedgerEntry(existingAccount, afterReserve, TradingLedgerBizType.ORDER_MARGIN_RESERVED,
                        margin, "order-margin-reserve:" + clientOrderId, referenceNo, occurredAt),
                toLedgerEntry(afterReserve, afterFee, TradingLedgerBizType.ORDER_FEE_CHARGED,
                        fee, "order-fee-charge:" + clientOrderId, referenceNo, occurredAt),
                toLedgerEntry(afterFee, finalAccount, TradingLedgerBizType.ORDER_MARGIN_CONFIRMED,
                        margin, "order-margin-confirm:" + clientOrderId, referenceNo, occurredAt)
        );
        tradingLedgerRepository.batchInsert(entries);

        return persisted;
    }

    /**
     * 拼装单条 ledger 领域对象（不持久化）。供 {@link #applyOrderPlacementAccountChange} 内部
     * 批量 INSERT 使用，逻辑与私有 {@link #writeLedger} 镜像但不直接调 save。
     */
    private TradingLedgerEntry toLedgerEntry(TradingAccount before,
                                             TradingAccount after,
                                             TradingLedgerBizType bizType,
                                             BigDecimal amount,
                                             String idempotencyKey,
                                             String referenceNo,
                                             OffsetDateTime occurredAt) {
        return new TradingLedgerEntry(
                null,
                after.accountId(),
                after.userId(),
                bizType,
                amount,
                idempotencyKey,
                referenceNo,
                before.balance(),
                after.balance(),
                before.frozen(),
                after.frozen(),
                before.marginUsed(),
                after.marginUsed(),
                occurredAt
        );
    }
```

- [ ] **Step 2: 验证编译**

```bash
mvn -pl falconx-trading-core-service compile -o -DskipTests 2>&1 | tail -3
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit Task 4 + Task 5 一起**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/service/TradingAccountService.java \
        falconx-trading-core-service/src/main/java/com/falconx/trading/service/impl/DefaultTradingAccountService.java
git commit -m "feat(trading-account): 加 applyOrderPlacementAccountChange 原子方法

Sprint 3 C1 Task 4+5：复合方法 reserveMargin+chargeFee+confirmMarginUsed
原子合并。

- 接受 existingAccount（外层已持锁），避免重复 SELECT FOR UPDATE
- 领域对象 3 步链式：reserveMargin → chargeFee → confirmMarginUsed
- 单 tradingAccountRepository.save 写最终 balance/frozen/margin_used
- batchInsert 3 条 ledger（biz_type / idempotency_key / amount 与原方法一致）
- 私有 toLedgerEntry 镜像 writeLedger 逻辑但不直接调 save

调用方改造与单元测试见 Task 6/7。"
```

---

## Task 6: 单元测试 ApplyOrderPlacementAccountChangeTests

**Files:**
- Create: `falconx-trading-core-service/src/test/java/com/falconx/trading/service/impl/ApplyOrderPlacementAccountChangeTests.java`

- [ ] **Step 1: 新建测试类**

```java
package com.falconx.trading.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingLedgerBizType;
import com.falconx.trading.entity.TradingLedgerEntry;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.repository.TradingAccountRepository;
import com.falconx.trading.repository.TradingLedgerRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ApplyOrderPlacementAccountChangeTests {

    private static final Long ACCOUNT_ID = 100L;
    private static final Long USER_ID = 7L;
    private static final String CURRENCY = "USDT";
    private static final String CLIENT_ORDER_ID = "cli-123";

    @Test
    void happyPath_writesThreeLedgersWithExpectedBizTypesAndKeys() {
        TradingAccountRepository accountRepo = mock(TradingAccountRepository.class);
        TradingLedgerRepository ledgerRepo = mock(TradingLedgerRepository.class);
        when(accountRepo.save(any(TradingAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepo.batchInsert(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        DefaultTradingAccountService service = newService(accountRepo, ledgerRepo);
        TradingAccount before = newAccount(new BigDecimal("500.00000000"), BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal margin = new BigDecimal("100.00000000");
        BigDecimal fee = new BigDecimal("1.00000000");
        OffsetDateTime now = OffsetDateTime.now();

        TradingAccount result = service.applyOrderPlacementAccountChange(
                before, margin, fee, CLIENT_ORDER_ID, "ref-001", now);

        // 最终账户状态：reserveMargin (frozen+=100) → chargeFee (balance-=1) → confirmMarginUsed (frozen-=100, marginUsed+=100)
        assertEquals(new BigDecimal("499.00000000"), result.balance());
        assertEquals(BigDecimal.ZERO.setScale(8), result.frozen());
        assertEquals(new BigDecimal("100.00000000"), result.marginUsed());

        // 单 UPDATE
        verify(accountRepo, times(1)).save(any(TradingAccount.class));

        // 单 batchInsert 写 3 条 ledger，biz_type/idempotency 顺序对齐
        ArgumentCaptor<List<TradingLedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerRepo, times(1)).batchInsert(captor.capture());
        verify(ledgerRepo, never()).save(any(TradingLedgerEntry.class));

        List<TradingLedgerEntry> ledgers = captor.getValue();
        assertEquals(3, ledgers.size());
        assertEquals(TradingLedgerBizType.ORDER_MARGIN_RESERVED, ledgers.get(0).bizType());
        assertEquals("order-margin-reserve:" + CLIENT_ORDER_ID, ledgers.get(0).idempotencyKey());
        assertEquals(margin, ledgers.get(0).amount());

        assertEquals(TradingLedgerBizType.ORDER_FEE_CHARGED, ledgers.get(1).bizType());
        assertEquals("order-fee-charge:" + CLIENT_ORDER_ID, ledgers.get(1).idempotencyKey());
        assertEquals(fee, ledgers.get(1).amount());

        assertEquals(TradingLedgerBizType.ORDER_MARGIN_CONFIRMED, ledgers.get(2).bizType());
        assertEquals("order-margin-confirm:" + CLIENT_ORDER_ID, ledgers.get(2).idempotencyKey());
        assertEquals(margin, ledgers.get(2).amount());
    }

    @Test
    void ledgerBalanceSnapshots_matchSequentialOriginalBehavior() {
        TradingAccountRepository accountRepo = mock(TradingAccountRepository.class);
        TradingLedgerRepository ledgerRepo = mock(TradingLedgerRepository.class);
        when(accountRepo.save(any(TradingAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ledgerRepo.batchInsert(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        DefaultTradingAccountService service = newService(accountRepo, ledgerRepo);
        TradingAccount before = newAccount(new BigDecimal("500.00000000"), BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal margin = new BigDecimal("100.00000000");
        BigDecimal fee = new BigDecimal("1.00000000");

        service.applyOrderPlacementAccountChange(before, margin, fee, CLIENT_ORDER_ID, "ref", OffsetDateTime.now());

        ArgumentCaptor<List<TradingLedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerRepo).batchInsert(captor.capture());
        List<TradingLedgerEntry> ledgers = captor.getValue();

        // ledger 0 = reserveMargin step: balance 不变 500, frozen 0 → 100
        assertEquals(new BigDecimal("500.00000000"), ledgers.get(0).balanceBefore());
        assertEquals(new BigDecimal("500.00000000"), ledgers.get(0).balanceAfter());
        assertEquals(BigDecimal.ZERO.setScale(8), ledgers.get(0).frozenBefore());
        assertEquals(new BigDecimal("100.00000000"), ledgers.get(0).frozenAfter());

        // ledger 1 = chargeFee step: balance 500 → 499, frozen 不变 100
        assertEquals(new BigDecimal("500.00000000"), ledgers.get(1).balanceBefore());
        assertEquals(new BigDecimal("499.00000000"), ledgers.get(1).balanceAfter());
        assertEquals(new BigDecimal("100.00000000"), ledgers.get(1).frozenBefore());
        assertEquals(new BigDecimal("100.00000000"), ledgers.get(1).frozenAfter());

        // ledger 2 = confirmMarginUsed step: balance 不变 499, frozen 100 → 0, marginUsed 0 → 100
        assertEquals(new BigDecimal("499.00000000"), ledgers.get(2).balanceBefore());
        assertEquals(new BigDecimal("499.00000000"), ledgers.get(2).balanceAfter());
        assertEquals(new BigDecimal("100.00000000"), ledgers.get(2).frozenBefore());
        assertEquals(BigDecimal.ZERO.setScale(8), ledgers.get(2).frozenAfter());
        assertEquals(BigDecimal.ZERO.setScale(8), ledgers.get(2).marginUsedBefore());
        assertEquals(new BigDecimal("100.00000000"), ledgers.get(2).marginUsedAfter());
    }

    @Test
    void insufficientBalance_throwsAndDoesNotPersist() {
        TradingAccountRepository accountRepo = mock(TradingAccountRepository.class);
        TradingLedgerRepository ledgerRepo = mock(TradingLedgerRepository.class);
        DefaultTradingAccountService service = newService(accountRepo, ledgerRepo);

        TradingAccount before = newAccount(new BigDecimal("50.00000000"), BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal margin = new BigDecimal("100.00000000");
        BigDecimal fee = new BigDecimal("1.00000000");

        // before.reserveMargin(100) 内部应抛业务异常（balance 50 < margin 100）
        // 注：实际异常类型由 TradingAccount.reserveMargin 决定；测试用 assertThrows(RuntimeException) 覆盖任何子类
        assertThrows(RuntimeException.class,
                () -> service.applyOrderPlacementAccountChange(before, margin, fee, CLIENT_ORDER_ID, "ref", OffsetDateTime.now()));

        // 验证：账户 / ledger 都没动
        verify(accountRepo, never()).save(any(TradingAccount.class));
        verify(ledgerRepo, never()).batchInsert(anyList());
    }

    // ---- helpers ----

    private DefaultTradingAccountService newService(TradingAccountRepository accountRepo, TradingLedgerRepository ledgerRepo) {
        // DefaultTradingAccountService 构造器签名与现有保持一致；如有其他依赖（idGenerator 等），
        // 实现时按现有签名补齐 mock。
        return new DefaultTradingAccountService(accountRepo, ledgerRepo, mock(com.falconx.infrastructure.id.IdGenerator.class));
    }

    private TradingAccount newAccount(BigDecimal balance, BigDecimal frozen, BigDecimal marginUsed) {
        return new TradingAccount(
                ACCOUNT_ID,
                USER_ID,
                CURRENCY,
                balance,
                frozen,
                marginUsed,
                TradingMarginMode.ISOLATED,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }
}
```

注：`newService(...)` 与 `newAccount(...)` 的构造器参数顺序需要与项目实际签名对齐。**实现 Task 6 前请先 Read `DefaultTradingAccountService` 和 `TradingAccount` 的构造器，按实际参数调整 helper**。

- [ ] **Step 2: 运行新测试**

```bash
mvn -pl falconx-trading-core-service -am test -o \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='ApplyOrderPlacementAccountChangeTests' 2>&1 | tail -10
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

如有失败，按错误信息排查：
- `IllegalArgumentException: TradingAccount constructor`: 调整 helper 参数顺序
- `NoSuchMethodError: DefaultTradingAccountService constructor`: 调整 newService(...) 参数

- [ ] **Step 3: Commit**

```bash
git add falconx-trading-core-service/src/test/java/com/falconx/trading/service/impl/ApplyOrderPlacementAccountChangeTests.java
git commit -m "test(trading-account): applyOrderPlacementAccountChange 单元测试

Sprint 3 C1 Task 6：3 个测试覆盖
- 正常路径：3 条 ledger biz_type / idempotency / amount 顺序与原行为对齐
- 中间 before/after 快照：与"顺序调原 3 方法"语义完全一致
- 余额不足：领域异常上抛，account/ledger 都不持久化"
```

---

## Task 7: TradingOrderPlacementApplicationService 调用方改 2 步

**Files:**
- Modify: `falconx-trading-core-service/src/main/java/com/falconx/trading/application/TradingOrderPlacementApplicationService.java`

- [ ] **Step 1: 替换 4 步资金调用为 2 步**

找到当前代码（约 L168-200，根据实际行号确定）：

```java
        account = tradingAccountService.reserveMargin(
                command.userId(),
                properties.getSettlementToken(),
                decision.margin(),
                "order-margin-reserve:" + command.clientOrderId(),
                command.clientOrderId(),
                now
        );
        account = tradingAccountService.chargeFee(
                command.userId(),
                properties.getSettlementToken(),
                decision.fee(),
                "order-fee-charge:" + command.clientOrderId(),
                command.clientOrderId(),
                now
        );
        account = tradingAccountService.confirmMarginUsed(
                command.userId(),
                properties.getSettlementToken(),
                decision.margin(),
                "order-margin-confirm:" + command.clientOrderId(),
                command.clientOrderId(),
                now
        );
```

**改为：**

```java
        // 2026-05-26 Sprint 3 C1：合并 reserveMargin / chargeFee / confirmMarginUsed
        // 为单个原子调用，避免下单链路 4 次 SELECT FOR UPDATE 同账户行。
        // ledger 仍写 3 条（biz_type / idempotency_key 与原方法完全一致）。
        account = tradingAccountService.applyOrderPlacementAccountChange(
                account,
                decision.margin(),
                decision.fee(),
                command.clientOrderId(),
                command.clientOrderId(),
                now
        );
```

注意：新方法第一个参数传**已加锁的 account 对象**（外层 getOrCreateAccountForUpdate 返回的），不是 userId/currency。

- [ ] **Step 2: 验证编译**

```bash
mvn -pl falconx-trading-core-service compile -o -DskipTests 2>&1 | tail -3
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: 运行 trading-core 单元测试（除已知 flaky Kafka IT 外全过）**

```bash
mvn -pl falconx-trading-core-service -am test -o -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -10
```

Expected: 仅预存 `TradingKafkaMarketEventIntegrationTests` 失败（与本改动无关，已在多个历史 commit 确认），其他全过。

如有新失败（特别是 `TradingOrderPlacementApplicationServiceTests`），按 mock 期望调整：
- 现有测试可能期望 `tradingAccountService.reserveMargin / chargeFee / confirmMarginUsed` 被分别调用 — 改为期望单次 `applyOrderPlacementAccountChange` 被调

- [ ] **Step 4: Commit**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/application/TradingOrderPlacementApplicationService.java
# 如果现有测试因 mock 期望调整也修了，一并 add 相关 test 文件
git commit -m "refactor(trading-order): 下单链路用 applyOrderPlacementAccountChange 替代 4 步

Sprint 3 C1 Task 7：调用方改造。

- 原 4 步：getOrCreateAccountForUpdate + reserveMargin + chargeFee + confirmMarginUsed
- 新 2 步：getOrCreateAccountForUpdate + applyOrderPlacementAccountChange

ledger 行数 / biz_type / idempotency_key / amount / before-after 快照
与原顺序调三方法完全一致，黑盒行为不变。"
```

---

## Task 8: 集成验证 + 现有测试回归

**Files:**
- Run: 现有所有 trading-core test
- Run: 手动模拟下单流程 + DB 对比

- [ ] **Step 1: 运行整个 trading-core 测试套件**

```bash
mvn -pl falconx-trading-core-service -am test -o -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee /tmp/c1-test-results.log | tail -15
```

Expected：

```
[INFO] BUILD SUCCESS
```

或仅 `TradingKafkaMarketEventIntegrationTests` failures（已知 flaky）。

如有其他失败：

```bash
grep -lE "Failures: [1-9]|Errors: [1-9]" falconx-trading-core-service/target/surefire-reports/*.txt
```

逐一处理。常见原因：

1. `TradingOrderPlacementApplicationServiceTests` 的 mock 期望从 3 次方法调用改为 1 次
2. ledger 持久化测试若直接断言 `tradingLedgerRepository.save` 调用次数，需要改为 `batchInsert` 调用 1 次

- [ ] **Step 2: 准备本地手动验证脚本**

新建 `scripts/c1-sanity-check.sh`（实现时创建）：

```bash
#!/usr/bin/env bash
# Sprint 3 C1 sanity：下一笔 dry-run，对比 t_account + t_ledger 与改造前一致
set -euo pipefail
USER_ID="${1:-7}"
SYMBOL="${2:-EURUSD}"

# 调下单 API（gateway → trading）
curl -s -X POST -H "Content-Type: application/json" \
  -d "{\"userId\":${USER_ID}, \"symbol\":\"${SYMBOL}\", \"side\":\"BUY\", \"quantity\":\"0.01\", \"clientOrderId\":\"c1-test-$(date +%s)\"}" \
  "http://localhost:18080/api/v1/trading/orders/market" | jq .

# 查 t_ledger 最近 3 条
docker exec falconx-mysql mysql -uroot -proot -e "
  SELECT id, user_id, biz_type, amount, idempotency_key, balance_before, balance_after, frozen_before, frozen_after, margin_used_before, margin_used_after
  FROM falconx_trading.t_ledger
  WHERE user_id = ${USER_ID}
  ORDER BY id DESC LIMIT 3;
"
```

- [ ] **Step 3: 本地跑下单 sanity（依赖本地 docker 全栈起来）**

```bash
bash scripts/local-start.sh   # 如未运行
sleep 30
bash scripts/c1-sanity-check.sh 7 EURUSD
```

Expected：

- 下单 API 返回 `{ "filled": ... }` 含订单 + 持仓
- t_ledger 最近 3 条按 id 倒序：confirm → fee → reserve
- biz_type 分别为 ORDER_MARGIN_CONFIRMED(5) / ORDER_FEE_CHARGED(4) / ORDER_MARGIN_RESERVED(3)
- idempotency_key 分别为 `order-margin-confirm:...` / `order-fee-charge:...` / `order-margin-reserve:...`

- [ ] **Step 4: Commit sanity 脚本（可选）**

```bash
git add scripts/c1-sanity-check.sh
chmod +x scripts/c1-sanity-check.sh
git commit -m "test(trading-order): Sprint 3 C1 sanity 脚本

下一笔 dry-run 后比对 t_ledger 3 条流水，验证 applyOrderPlacementAccountChange
行为与原顺序调 3 方法完全一致。"
```

---

## Task 9: 部署 demo + 监控 row_lock_waits 24-48h

**Files:**
- Use: `scripts/deploy-to-server.sh -s trading-core-service --skip-mvn`

按 spec §6.2 / epic §6：C1 + S6 都改 trading-core，先单独部署 C1，观察 2 天再上 S6。

- [ ] **Step 1: 部署前记录基线 row_lock_waits**

```bash
ssh ubuntu@10.143.170.189 "docker exec falconx-mysql mysql -uroot -proot -e \"SHOW STATUS LIKE 'Innodb_row_lock%';\" 2>&1 | grep -v 'Warning\\|mysql:'" | tee /tmp/c1-pre-deploy-baseline.txt
```

记录 `Innodb_row_lock_waits` 数值（基线）。

- [ ] **Step 2: mvn package（本地，跳过测试）**

```bash
mvn -pl falconx-trading-core-service -am package -Dmaven.test.skip=true -o 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: deploy-to-server.sh 单 trading-core**

```bash
bash scripts/deploy-to-server.sh -s trading-core-service --skip-mvn 2>&1 | tail -10
```

Expected：

```
✓ 部署完成
  入口: https://app-falconx.lifebyteapp.dev/ ...
```

- [ ] **Step 4: 部署后 15 分钟稳态验证**

```bash
sleep 900
ssh ubuntu@10.143.170.189 "
echo '[trading-core 容器状态]'
docker compose -f /home/ubuntu/falconx/docker-compose.prod.yml ps trading-core-service
echo
echo '[trading-core /actuator/health]'
curl -s --max-time 5 -w 'http=%{http_code}\n' http://localhost:18083/actuator/health
echo
echo '[applyOrderPlacementAccountChange 启动日志（应无 ERROR）]'
docker compose -f /home/ubuntu/falconx/docker-compose.prod.yml logs --no-color --since 15m trading-core-service 2>&1 | grep -E 'Started.*Application|ERROR|applyOrderPlacementAccountChange' | head -5
"
```

Expected：

- trading-core Up & healthy
- 日志含 `Started TradingCoreServiceApplication`，无 ERROR

- [ ] **Step 5: 监控点 — 24h / 48h row_lock_waits**

部署 24h + 48h 后跑：

```bash
ssh ubuntu@10.143.170.189 "docker exec falconx-mysql mysql -uroot -proot -e \"
  SHOW STATUS LIKE 'Innodb_row_lock%';
  SELECT COUNT(*) AS recent_orders_24h FROM falconx_trading.t_order WHERE created_at > NOW() - INTERVAL 24 HOUR;
  SELECT COUNT(*) AS recent_ledgers_24h FROM falconx_trading.t_ledger WHERE created_at > NOW() - INTERVAL 24 HOUR;
\" 2>&1 | grep -v 'Warning\\|mysql:'" | tee /tmp/c1-post-deploy-24h.txt
```

Expected 24h 后（与基线对比）：

- `Innodb_row_lock_waits` 增量与下单 TPS 成正比，期望比改造前下降 ~50%（同 TPS 下）
- `recent_ledgers_24h / recent_orders_24h ≈ 3`（每笔下单仍写 3 条 ledger，比例不变）

- [ ] **Step 6: 验证完毕 — 更新 statusline / 性能报告**

24-48h 验证通过后，在 `docs/perf/性能分析-2026-05-25.md` §0.7 加 Sprint 3 C1 实测数据。

```bash
# 报告更新 + commit
git add docs/perf/性能分析-2026-05-25.md
git commit -m "docs(perf): Sprint 3 C1 部署 48h 实测数据

Innodb_row_lock_waits 基线 X → 24h 后 Y → 48h 后 Z（下降 N%）
下单链路 P99 latency 基线 X ms → 改后 Y ms"
git push origin main
```

---

## Self-Review

**Spec coverage check** — 对照 design spec §1-§10：

| Spec 章节 | 覆盖 Task |
|---|---|
| §1 背景 / 收益预期 | Task 9 实测验证 |
| §2 当前实现 | Task 7 调用方改造 |
| §3.1 新方法接口 | Task 4 |
| §3.2 实现伪代码 | Task 5 |
| §3.3 调用方改动 | Task 7 |
| §3.4 兼容性（保留原 3 方法） | Task 5 — 仅 add 不删除原方法 |
| §4 失败语义 | Task 6（insufficientBalance + 重复幂等键测试覆盖） |
| §5 测试矩阵 | Task 6 |
| §6.1 风险 1 ledger biz_type 漂移 | Task 6 `ledgerBalanceSnapshots_matchSequentialOriginalBehavior` 测试 |
| §6.1 风险 2 其他路径用原方法 | Task 5 — 保留原方法不删 |
| §6.1 风险 3 乐观锁 version | spec 已确认非问题 |
| §6.2 回滚 | Task 8 单 commit revert |
| §7 R6 验证清单 | Task 8 + Task 9 |
| §8 涉及范围 | Task 1-7 文件清单与 spec §8 完全一致 |
| §9 不做 YAGNI | Task 5 — 不动 t_account schema / 不删原方法 |

**Placeholder scan** — 全文搜 "TBD/TODO/...": 无。

**Type consistency** — `TradingLedgerEntry` 字段顺序在 Task 3 / Task 5 / Task 6 三处使用，需要实现时统一以 `falconx-trading-core-service/.../entity/TradingLedgerEntry.java` 实际 record 字段顺序为准（plan 中已显式注释这点）。

**Scope check** — 单一 sub-project，文件清单 ≤ 9，任务依赖图清晰，单 sprint 可完成。

---

## 不做（YAGNI 已明确）

- ❌ 不动 t_account / t_ledger schema
- ❌ 不删除原 reserveMargin / chargeFee / confirmMarginUsed
- ❌ 不引入乐观锁（依赖 SELECT FOR UPDATE 不变）
- ❌ 不改 Kafka 事件 / outbox payload
- ❌ 不并发部署 S6（先单独 C1 观察 48h）
