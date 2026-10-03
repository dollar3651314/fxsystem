# S6 — TradingPositionCloseApplicationService 事务拆分实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `settlePositionExit` 单事务跨 7 张表（t_account + t_ledger + t_position + t_trade + t_risk_exposure + t_liquidation_log + t_outbox + t_pending_order_trigger 级联）拆分为：主事务仅保留强一致 5 项（t_account / t_ledger / t_position / t_risk_exposure / t_outbox），其余 t_trade / t_liquidation_log / t_pending_order_trigger 级联撤通过 outbox post-process 事件异步落盘。

**Architecture:** 采用 spec 方案 B（已用户 approve）。在主事务内提前 `idGenerator.nextId()` reserve tradeId（不写 DB），用此 id 同时构造 in-memory `TradingTrade` 返回响应 + 写入 outbox post-process payload；新增 `TradingPositionClosePostProcessOutboxConsumer` 自产自销订阅 `falconx.trading.position.close.post-process` topic，按 event_type 分发写入 t_trade / t_liquidation_log / 级联撤 SL_TP。保留 feature flag `falconx.trading.position.close.async-post-process.enabled` 便于回退。

**Tech Stack:** Java 25 + Spring Boot 4 + Kafka 4.2 + MyBatis 3.5 + 现有 trading outbox 框架。

**Design spec:** [`docs/superpowers/specs/2026-05-26-s6-trading-position-close-tx-split-design.md`](../specs/2026-05-26-s6-trading-position-close-tx-split-design.md)

**Epic:** [`docs/superpowers/specs/2026-05-26-sprint-3-epic-overview.md`](../specs/2026-05-26-sprint-3-epic-overview.md)

**部署前提：** **必须等 C1 部署 demo 后稳定 48h 无回归，才能上线 S6**（spec §6.2 + epic §6）。

---

## File Structure

| 文件 | 用途 | Task |
|---|---|---|
| `falconx-trading-core-service/src/main/java/com/falconx/trading/repository/TradingTradeRepository.java` | 接口加 `saveWithExplicitId(TradingTrade)` | Task 1 |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/repository/MybatisTradingTradeRepository.java` | 实现 saveWithExplicitId | Task 1 |
| `falconx-trading-core-service/src/main/resources/application.yml` | 加 feature flag + topic 配置 | Task 2 |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/application/TradingPositionCloseApplicationService.java` | settlePositionExit 主事务拆分 + post-process outbox payload builder | Task 3 |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/consumer/TradingPositionClosePostProcessConsumer.java`（新） | 订阅 `falconx.trading.position.close.post-process` topic + event_type 分发 | Task 4 |
| `falconx-trading-core-service/src/main/java/com/falconx/trading/config/TradingCoreServiceConfiguration.java` | Kafka topic 注册（若现有 config 集中管理） | Task 4 (合并) |
| `falconx-trading-core-service/src/test/java/com/falconx/trading/application/TradingPositionCloseApplicationServiceS6Tests.java`（新） | 主事务拆分单测 | Task 5 |
| `falconx-trading-core-service/src/test/java/com/falconx/trading/consumer/TradingPositionClosePostProcessConsumerTests.java`（新） | 消费者单测 | Task 5 |
| `scripts/s6-sanity-check.sh`（新） | 手动平仓 → 5s 后检查 t_trade 是否落盘 | Task 6 |

任务依赖：`1 → 2 → 3 → 4 → 5 → 6 → 7 → 8`。Task 1 提供消费者用的写 API；Task 2 配置；Task 3 主事务拆分（写 post-process outbox payload）；Task 4 消费者；Task 5/6 测试；Task 7 集成；Task 8 部署（**等 C1 稳定 48h**）。

---

## Task 1: TradingTradeRepository 加 saveWithExplicitId

**Files:**
- Modify: `falconx-trading-core-service/src/main/java/com/falconx/trading/repository/TradingTradeRepository.java`
- Modify: `falconx-trading-core-service/src/main/java/com/falconx/trading/repository/MybatisTradingTradeRepository.java`
- Create test: `falconx-trading-core-service/src/test/java/com/falconx/trading/repository/MybatisTradingTradeRepositorySaveWithExplicitIdTests.java`

**背景：** 当前 `MybatisTradingTradeRepository.save` 在 `trade.tradeId() != null` 时**直接 return 不 INSERT**（见现有代码 L33-52）。消费者需要 INSERT 用 reserved id 的 trade，必须新增显式方法。

- [ ] **Step 1: 写失败测试**

```java
package com.falconx.trading.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.entity.TradingTradeType;
import com.falconx.trading.repository.mapper.TradingTradeMapper;
import com.falconx.trading.repository.mapper.record.TradingTradeRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MybatisTradingTradeRepositorySaveWithExplicitIdTests {

    @Test
    void saveWithExplicitId_insertsWithProvidedId() {
        TradingTradeMapper mapper = mock(TradingTradeMapper.class);
        IdGenerator idGen = mock(IdGenerator.class);
        MybatisTradingTradeRepository repo = new MybatisTradingTradeRepository(mapper, idGen);
        OffsetDateTime now = OffsetDateTime.now();
        TradingTrade trade = new TradingTrade(
                5001L,                                  // explicit id
                100L,                                   // orderId
                200L,                                   // positionId
                7L,                                     // userId
                "EURUSD",
                TradingOrderSide.BUY,
                TradingTradeType.CLOSE,
                new BigDecimal("0.01"),
                new BigDecimal("1.10000000"),
                BigDecimal.ZERO.setScale(8),
                new BigDecimal("0.50000000"),
                now
        );

        TradingTrade result = repo.saveWithExplicitId(trade);

        // 验证返回的是带 explicit id 的同一对象
        assertSame(trade, result);
        assertEquals(5001L, result.tradeId());

        // 验证 mapper.insertTradingTrade 被调用 1 次，record id = 5001L
        ArgumentCaptor<TradingTradeRecord> captor = ArgumentCaptor.forClass(TradingTradeRecord.class);
        verify(mapper).insertTradingTrade(captor.capture());
        assertEquals(5001L, captor.getValue().id());
    }

    @Test
    void saveWithExplicitId_rejectsNullId() {
        TradingTradeMapper mapper = mock(TradingTradeMapper.class);
        IdGenerator idGen = mock(IdGenerator.class);
        MybatisTradingTradeRepository repo = new MybatisTradingTradeRepository(mapper, idGen);

        TradingTrade tradeWithNullId = new TradingTrade(
                null,                                   // null id 不允许
                100L, 200L, 7L, "EURUSD",
                TradingOrderSide.BUY, TradingTradeType.CLOSE,
                new BigDecimal("0.01"), new BigDecimal("1.10000000"),
                BigDecimal.ZERO.setScale(8), BigDecimal.ZERO.setScale(8),
                OffsetDateTime.now()
        );

        assertThrows(IllegalArgumentException.class, () -> repo.saveWithExplicitId(tradeWithNullId));
        verify(mapper, org.mockito.Mockito.never()).insertTradingTrade(org.mockito.ArgumentMatchers.any());
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn -pl falconx-trading-core-service -am test -o \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='MybatisTradingTradeRepositorySaveWithExplicitIdTests' 2>&1 | tail -8
```

Expected: BUILD FAILURE / `cannot find symbol: method saveWithExplicitId`

- [ ] **Step 3: TradingTradeRepository 接口加方法**

在 `save(TradingTrade trade);` 之后插入：

```java
    /**
     * 用调用方提供的 id 持久化 trade（不重新生成雪花 id）。
     *
     * <p>专为 Sprint 3 S6 异步消费 t_trade 写入设计：主事务 reserve id 后通过
     * outbox payload 传给消费者，消费者用此方法写入保证 in-memory trade 对象与
     * DB t_trade.id 完全一致。
     *
     * @param trade 必须含非 null {@code tradeId} 的 TradingTrade 对象
     * @return 入参对象（直接 return，不修改）
     * @throws IllegalArgumentException 当 trade.tradeId() == null
     */
    TradingTrade saveWithExplicitId(TradingTrade trade);
```

- [ ] **Step 4: MybatisTradingTradeRepository 实现**

在 `save(TradingTrade trade)` 方法之后插入：

```java
    @Override
    public TradingTrade saveWithExplicitId(TradingTrade trade) {
        if (trade.tradeId() == null) {
            throw new IllegalArgumentException("saveWithExplicitId requires non-null tradeId");
        }
        tradingTradeMapper.insertTradingTrade(toRecord(trade));
        return trade;
    }
```

（依赖现有私有方法 `toRecord(TradingTrade)`，无需新增。）

- [ ] **Step 5: 运行测试验证通过**

```bash
mvn -pl falconx-trading-core-service -am test -o \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='MybatisTradingTradeRepositorySaveWithExplicitIdTests' 2>&1 | tail -8
```

Expected: `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 6: Commit**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/repository/TradingTradeRepository.java \
        falconx-trading-core-service/src/main/java/com/falconx/trading/repository/MybatisTradingTradeRepository.java \
        falconx-trading-core-service/src/test/java/com/falconx/trading/repository/MybatisTradingTradeRepositorySaveWithExplicitIdTests.java
git commit -m "feat(trading-trade): TradingTradeRepository 加 saveWithExplicitId

Sprint 3 S6 Task 1：消费者用主事务 reserve 的 tradeId 写入 t_trade，
保证 in-memory trade 对象与 DB 落盘后 id 一致。

现有 save(TradingTrade) 在 tradeId != null 时直接 return 不 INSERT，
是历史遗留 dead branch，本任务不动它；新增 explicit 方法语义更清晰。"
```

---

## Task 2: application.yml 加 feature flag + topic 配置

**Files:**
- Modify: `falconx-trading-core-service/src/main/resources/application.yml`

- [ ] **Step 1: 在 `falconx.trading` 配置段下加 feature flag + Kafka topic**

定位现有 `falconx.trading` yml 段，在其下加：

```yaml
falconx:
  trading:
    # 现有配置...
    position-close:
      # S6（Sprint 3 / 性能报告 §6 P0）：true=异步 post-process（t_trade/
      # t_liquidation_log/级联撤 SL_TP 走 outbox 异步落盘）；false=回退到同步主事务。
      # 默认 true；问题排查时设 false 1 周观察，确认稳定后才删除旧分支。
      async-post-process:
        enabled: true
      post-process:
        topic: falconx.trading.position.close.post-process
        # 消费者并发度：自产自销单 partition 即可
        concurrency: 1
```

注：实际 yml 缩进与现有配置对齐；`falconx.trading.kafka.*` 段如已存在 topic 列表，把新 topic 加进去。

- [ ] **Step 2: 验证 yml 解析（启动 Spring app）**

```bash
mvn -pl falconx-trading-core-service compile -o -DskipTests 2>&1 | tail -3
```

Expected: `BUILD SUCCESS`（yml 在编译期不严格校验，运行时由 ConfigurationProperties 校验）

- [ ] **Step 3: Commit**

```bash
git add falconx-trading-core-service/src/main/resources/application.yml
git commit -m "config(trading-position-close): 加 S6 async-post-process feature flag + topic

Sprint 3 S6 Task 2。

- falconx.trading.position-close.async-post-process.enabled: true（默认启用异步）
- falconx.trading.position-close.post-process.topic: falconx.trading.position.close.post-process
- concurrency: 1（自产自销单 partition 足够）"
```

---

## Task 3: settlePositionExit 主事务拆分

**Files:**
- Modify: `falconx-trading-core-service/src/main/java/com/falconx/trading/application/TradingPositionCloseApplicationService.java`

- [ ] **Step 1: 注入 IdGenerator + 读 feature flag**

在 class 顶部 fields 加：

```java
    private final com.falconx.infrastructure.id.IdGenerator idGenerator;
    private final boolean asyncPostProcessEnabled;
    private final String postProcessTopic;
```

修改构造函数，加 @Value 注入：

```java
    public TradingPositionCloseApplicationService(
            // 现有参数...
            com.falconx.infrastructure.id.IdGenerator idGenerator,
            @org.springframework.beans.factory.annotation.Value("${falconx.trading.position-close.async-post-process.enabled:true}") boolean asyncPostProcessEnabled,
            @org.springframework.beans.factory.annotation.Value("${falconx.trading.position-close.post-process.topic}") String postProcessTopic) {
        // 现有赋值...
        this.idGenerator = idGenerator;
        this.asyncPostProcessEnabled = asyncPostProcessEnabled;
        this.postProcessTopic = postProcessTopic;
    }
```

- [ ] **Step 2: 重写 settlePositionExit 方法（异步分支）**

替换现有 settlePositionExit 内部逻辑（行 242-360 范围），保留 if 分支 fallback 到同步路径：

```java
    private PositionCloseResult settlePositionExit(TradingPosition position,
                                                   TradingQuoteSnapshot quote,
                                                   TradingPositionCloseReason closeReason,
                                                   OffsetDateTime occurredAt) {
        // 1. 行情 / PnL 计算 / 账户取锁（不变）
        BigDecimal effectiveMarkPrice = TradingPricingSupport.resolvePositionMarkPrice(quote, position);
        if (effectiveMarkPrice == null) {
            throw new TradingBusinessException(TradingErrorCode.QUOTE_NOT_AVAILABLE);
        }
        BigDecimal realizedPnl = calculateRealizedPnl(position, effectiveMarkPrice);
        TradingAccount settlementAccount = tradingAccountService.getExistingAccountForUpdate(
                position.userId(), properties.getSettlementToken());

        boolean liquidation = closeReason == TradingPositionCloseReason.LIQUIDATION;

        // 2. 账户结算（t_account + t_ledger）—— 保留主事务
        TradingAccountService.PositionSettlementResult settlement = tradingAccountService.settlePositionExit(
                settlementAccount, position.margin(), realizedPnl,
                liquidation ? TradingLedgerBizType.LIQUIDATION_PNL : TradingLedgerBizType.REALIZED_PNL,
                liquidation, "position-exit:" + position.positionId() + ":" + closeReason.name(),
                String.valueOf(position.positionId()), occurredAt);

        // 3. 持仓状态机（t_position）—— 保留主事务
        TradingPositionStatus nextStatus = liquidation ? TradingPositionStatus.LIQUIDATED : TradingPositionStatus.CLOSED;
        TradingTradeType tradeType = liquidation ? TradingTradeType.LIQUIDATION : TradingTradeType.CLOSE;
        TradingPosition exitedPosition = tradingPositionRepository.save(position.close(
                nextStatus, closeReason, effectiveMarkPrice, realizedPnl, occurredAt));

        // 4. 敞口（t_risk_exposure）—— 保留主事务（B 方案：实时风控敏感）
        tradingRiskObservabilityService.applyClosePosition(
                position.symbol(), position.side(), position.quantity(),
                quote, occurredAt, closeReason, position.positionId());

        // 5. Reserve tradeId（不写 DB，仅生成雪花 id）
        long reservedTradeId = idGenerator.nextId();

        // 6. 构造 in-memory trade 对象（同步分支用于直接调 repo，异步分支用于响应 + outbox payload）
        TradingTrade inMemoryTrade = new TradingTrade(
                reservedTradeId, position.openingOrderId(), position.positionId(),
                position.userId(), position.symbol(), position.side(), tradeType,
                position.quantity(), effectiveMarkPrice, BigDecimal.ZERO.setScale(8),
                realizedPnl, occurredAt);

        TradingLiquidationLog liquidationLog = null;
        TradingTrade persistedTrade;

        if (asyncPostProcessEnabled) {
            // ===== 异步路径（S6 改造）=====
            // 主事务里只写 outbox post-process 事件，由消费者异步写入 t_trade / t_liquidation_log /
            // 级联撤 SL_TP。in-memory trade 对象返回给客户端使用。

            // 6.1 主事件 outbox（position.closed / liquidated）— 与现状一致
            tradingOutboxRepository.save(liquidation
                    ? buildLiquidationOutbox(exitedPosition, inMemoryTrade, settlement, null, occurredAt)
                    : buildPositionClosedOutbox(exitedPosition, inMemoryTrade, occurredAt));

            // 6.2 post-process: t_trade 异步写
            tradingOutboxRepository.save(buildTradeWriteOutbox(reservedTradeId, exitedPosition,
                    inMemoryTrade, occurredAt));

            // 6.3 post-process: t_liquidation_log 异步写（仅强平）
            if (liquidation) {
                tradingOutboxRepository.save(buildLiquidationLogWriteOutbox(exitedPosition,
                        effectiveMarkPrice, realizedPnl, settlement, quote, occurredAt));
            }

            // 6.4 post-process: 级联撤 SL_TP 异步
            if (pendingOrderRepository != null) {
                tradingOutboxRepository.save(buildPendingOrderCascadeCancelOutbox(
                        exitedPosition, closeReason, occurredAt));
            }

            persistedTrade = inMemoryTrade;
            // liquidationLog 在异步分支 = null，PositionCloseResult 中由消费者写完后查询拿到
        } else {
            // ===== 同步回退路径（feature flag = false）=====
            persistedTrade = tradingTradeRepository.save(new TradingTrade(
                    null, // 让 save 走原雪花 id 路径，不复用 reservedTradeId
                    position.openingOrderId(), position.positionId(),
                    position.userId(), position.symbol(), position.side(), tradeType,
                    position.quantity(), effectiveMarkPrice, BigDecimal.ZERO.setScale(8),
                    realizedPnl, occurredAt));

            if (liquidation) {
                liquidationLog = tradingLiquidationLogRepository.save(new TradingLiquidationLog(
                        null, position.userId(), position.positionId(), position.symbol(),
                        position.side(), position.marginMode(), position.quantity(),
                        position.entryPrice(), position.liquidationPrice(), effectiveMarkPrice,
                        quote.ts(), quote.source(),
                        realizedPnl.signum() < 0 ? realizedPnl.abs() : BigDecimal.ZERO.setScale(8),
                        BigDecimal.ZERO.setScale(8), position.margin(),
                        settlement.platformCoveredLoss(), occurredAt));
            }

            tradingOutboxRepository.save(liquidation
                    ? buildLiquidationOutbox(exitedPosition, persistedTrade, settlement, liquidationLog, occurredAt)
                    : buildPositionClosedOutbox(exitedPosition, persistedTrade, occurredAt));

            if (pendingOrderRepository != null) {
                try {
                    int cancelled = pendingOrderRepository.cancelAllSlTpByPositionId(
                            position.positionId(), "PARENT_POSITION_CLOSED_BY_" + closeReason.name());
                    if (cancelled > 0) {
                        log.info("trading.pending-order.sl-tp.parent-closed positionId={} cancelled={}",
                                position.positionId(), cancelled);
                    }
                } catch (RuntimeException ex) {
                    log.warn("trading.pending-order.sl-tp.cascade-failed positionId={} message={}",
                            position.positionId(), ex.getMessage());
                }
            }
        }

        registerSnapshotRemoval(exitedPosition, persistedTrade, settlement.account(), liquidationLog);

        if (liquidation) {
            log.warn("trading.liquidation.executed userId={} positionId={} closePrice={} realizedPnl={} async={}",
                    position.userId(), position.positionId(), effectiveMarkPrice, realizedPnl, asyncPostProcessEnabled);
        }
        log.info("trading.position.exit.completed userId={} positionId={} reason={} status={} closePrice={} realizedPnl={} async={}",
                position.userId(), position.positionId(), closeReason, nextStatus,
                effectiveMarkPrice, realizedPnl, asyncPostProcessEnabled);

        return new PositionCloseResult(exitedPosition, persistedTrade, settlement, liquidationLog);
    }
```

- [ ] **Step 3: 加 3 个 outbox payload builder**

在 class 底部 `buildLiquidationOutbox` 之后插入：

```java
    private TradingOutboxMessage buildTradeWriteOutbox(long tradeId,
                                                       TradingPosition position,
                                                       TradingTrade trade,
                                                       OffsetDateTime occurredAt) {
        return new TradingOutboxMessage(
                null,
                "trade-write:" + position.positionId() + ":" + tradeId,
                "trading.position.close.post-process",
                String.valueOf(position.userId()),
                Map.ofEntries(
                        Map.entry("eventType", "TRADE_WRITE"),
                        Map.entry("tradeId", tradeId),
                        Map.entry("orderId", trade.orderId()),
                        Map.entry("positionId", position.positionId()),
                        Map.entry("userId", position.userId()),
                        Map.entry("symbol", position.symbol()),
                        Map.entry("side", position.side().name()),
                        Map.entry("tradeType", trade.tradeType().name()),
                        Map.entry("quantity", trade.quantity()),
                        Map.entry("price", trade.price()),
                        Map.entry("fee", trade.fee()),
                        Map.entry("realizedPnl", trade.realizedPnl()),
                        Map.entry("tradedAt", trade.tradedAt())
                ),
                occurredAt
        );
    }

    private TradingOutboxMessage buildLiquidationLogWriteOutbox(TradingPosition position,
                                                                 BigDecimal effectiveMarkPrice,
                                                                 BigDecimal realizedPnl,
                                                                 TradingAccountService.PositionSettlementResult settlement,
                                                                 TradingQuoteSnapshot quote,
                                                                 OffsetDateTime occurredAt) {
        return new TradingOutboxMessage(
                null,
                "liquidation-log-write:" + position.positionId(),
                "trading.position.close.post-process",
                String.valueOf(position.userId()),
                Map.ofEntries(
                        Map.entry("eventType", "LIQUIDATION_LOG_WRITE"),
                        Map.entry("userId", position.userId()),
                        Map.entry("positionId", position.positionId()),
                        Map.entry("symbol", position.symbol()),
                        Map.entry("side", position.side().name()),
                        Map.entry("marginMode", position.marginMode().name()),
                        Map.entry("quantity", position.quantity()),
                        Map.entry("entryPrice", position.entryPrice()),
                        Map.entry("liquidationPrice", position.liquidationPrice()),
                        Map.entry("closePrice", effectiveMarkPrice),
                        Map.entry("quoteTs", quote.ts()),
                        Map.entry("quoteSource", quote.source()),
                        Map.entry("netLossAfterMargin", realizedPnl.signum() < 0 ? realizedPnl.abs() : BigDecimal.ZERO.setScale(8)),
                        Map.entry("liquidationFee", BigDecimal.ZERO.setScale(8)),
                        Map.entry("marginReleased", position.margin()),
                        Map.entry("platformCoveredLoss", settlement.platformCoveredLoss()),
                        Map.entry("occurredAt", occurredAt)
                ),
                occurredAt
        );
    }

    private TradingOutboxMessage buildPendingOrderCascadeCancelOutbox(TradingPosition position,
                                                                       TradingPositionCloseReason closeReason,
                                                                       OffsetDateTime occurredAt) {
        return new TradingOutboxMessage(
                null,
                "pending-order-cascade-cancel:" + position.positionId(),
                "trading.position.close.post-process",
                String.valueOf(position.userId()),
                Map.of(
                        "eventType", "PENDING_ORDER_CASCADE_CANCEL",
                        "positionId", position.positionId(),
                        "closeReason", closeReason.name()
                ),
                occurredAt
        );
    }
```

- [ ] **Step 4: 验证编译**

```bash
mvn -pl falconx-trading-core-service compile -o -DskipTests 2>&1 | tail -3
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/application/TradingPositionCloseApplicationService.java
git commit -m "refactor(trading-position-close): settlePositionExit 主事务拆分（S6 Task 3）

Sprint 3 S6 方案 B：
- 主事务保留：t_account + t_ledger + t_position + t_risk_exposure + t_outbox
- 异步 outbox post-process：t_trade + t_liquidation_log + SL_TP 级联撤
- 主事务内 reserve tradeId，构造 in-memory trade 对象同时返回响应 + 写入
  outbox payload，保证 in-memory 与 DB 落盘一致

保留 falconx.trading.position-close.async-post-process.enabled=false
回退路径走原同步逻辑，所有 7 张表仍在主事务内。

PositionCloseResult API 响应结构不变。"
```

---

## Task 4: TradingPositionClosePostProcessConsumer 新建

**Files:**
- Create: `falconx-trading-core-service/src/main/java/com/falconx/trading/consumer/TradingPositionClosePostProcessConsumer.java`

- [ ] **Step 1: 新建消费者类**

```java
package com.falconx.trading.consumer;

import com.falconx.trading.entity.TradingLiquidationLog;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPositionCloseReason;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.entity.TradingTradeType;
import com.falconx.trading.repository.TradingLiquidationLogRepository;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.TradingTradeRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Sprint 3 S6：消费 {@code falconx.trading.position.close.post-process} topic，按
 * {@code eventType} 分发写入 t_trade / t_liquidation_log / 级联撤 SL_TP 挂单。
 *
 * <p>自产自销：本 service 自己 produce（通过 outbox dispatcher）+ 自己 consume，
 * 主要目的是把 close 主事务的范围缩小。失败由 Kafka consumer retry 机制兜底。
 *
 * <p>每个 sub-handler 独立 {@code @Transactional} —— 任一失败不影响其他 sub-event。
 */
@Component
public class TradingPositionClosePostProcessConsumer {

    private static final Logger log = LoggerFactory.getLogger(TradingPositionClosePostProcessConsumer.class);

    private final TradingTradeRepository tradingTradeRepository;
    private final TradingLiquidationLogRepository tradingLiquidationLogRepository;
    private final TradingPendingOrderTriggerRepository pendingOrderRepository;

    public TradingPositionClosePostProcessConsumer(
            TradingTradeRepository tradingTradeRepository,
            TradingLiquidationLogRepository tradingLiquidationLogRepository,
            TradingPendingOrderTriggerRepository pendingOrderRepository) {
        this.tradingTradeRepository = tradingTradeRepository;
        this.tradingLiquidationLogRepository = tradingLiquidationLogRepository;
        this.pendingOrderRepository = pendingOrderRepository;
    }

    @KafkaListener(
            topics = "${falconx.trading.position-close.post-process.topic}",
            groupId = "falconx.trading-core-service.position-close-post-process",
            concurrency = "${falconx.trading.position-close.post-process.concurrency:1}"
    )
    public void consume(String eventId, JsonNode payload) {
        String eventType = payload.get("eventType").asString();
        try {
            switch (eventType) {
                case "TRADE_WRITE" -> handleTradeWrite(payload);
                case "LIQUIDATION_LOG_WRITE" -> handleLiquidationLogWrite(payload);
                case "PENDING_ORDER_CASCADE_CANCEL" -> handlePendingOrderCascadeCancel(payload);
                default -> log.warn("trading.position-close.post-process.unknown-event eventId={} eventType={}", eventId, eventType);
            }
        } catch (RuntimeException ex) {
            log.error("trading.position-close.post-process.failed eventId={} eventType={} reason={}",
                    eventId, eventType, ex.toString(), ex);
            throw ex;  // 重抛由 Spring Kafka retry / DLT 处理
        }
    }

    @Transactional
    void handleTradeWrite(JsonNode payload) {
        Long tradeId = payload.get("tradeId").asLong();
        TradingTrade trade = new TradingTrade(
                tradeId,
                payload.get("orderId").asLong(),
                payload.get("positionId").asLong(),
                payload.get("userId").asLong(),
                payload.get("symbol").asString(),
                TradingOrderSide.valueOf(payload.get("side").asString()),
                TradingTradeType.valueOf(payload.get("tradeType").asString()),
                new BigDecimal(payload.get("quantity").asString()),
                new BigDecimal(payload.get("price").asString()),
                new BigDecimal(payload.get("fee").asString()),
                new BigDecimal(payload.get("realizedPnl").asString()),
                OffsetDateTime.parse(payload.get("tradedAt").asString())
        );
        tradingTradeRepository.saveWithExplicitId(trade);
        log.info("trading.position-close.post-process.trade-write.completed tradeId={} positionId={}",
                tradeId, trade.positionId());
    }

    @Transactional
    void handleLiquidationLogWrite(JsonNode payload) {
        TradingLiquidationLog log_ = new TradingLiquidationLog(
                null,                                                   // id 由 repo 生成（liquidation log 不需要 reserved id）
                payload.get("userId").asLong(),
                payload.get("positionId").asLong(),
                payload.get("symbol").asString(),
                TradingOrderSide.valueOf(payload.get("side").asString()),
                TradingMarginMode.valueOf(payload.get("marginMode").asString()),
                new BigDecimal(payload.get("quantity").asString()),
                new BigDecimal(payload.get("entryPrice").asString()),
                new BigDecimal(payload.get("liquidationPrice").asString()),
                new BigDecimal(payload.get("closePrice").asString()),
                OffsetDateTime.parse(payload.get("quoteTs").asString()),
                payload.get("quoteSource").asString(),
                new BigDecimal(payload.get("netLossAfterMargin").asString()),
                new BigDecimal(payload.get("liquidationFee").asString()),
                new BigDecimal(payload.get("marginReleased").asString()),
                new BigDecimal(payload.get("platformCoveredLoss").asString()),
                OffsetDateTime.parse(payload.get("occurredAt").asString())
        );
        tradingLiquidationLogRepository.save(log_);
        log.info("trading.position-close.post-process.liquidation-log-write.completed positionId={}",
                log_.positionId());
    }

    @Transactional
    void handlePendingOrderCascadeCancel(JsonNode payload) {
        Long positionId = payload.get("positionId").asLong();
        String closeReason = payload.get("closeReason").asString();
        int cancelled = pendingOrderRepository.cancelAllSlTpByPositionId(
                positionId, "PARENT_POSITION_CLOSED_BY_" + closeReason);
        log.info("trading.position-close.post-process.pending-order-cascade-cancel.completed positionId={} cancelled={}",
                positionId, cancelled);
    }
}
```

注：JsonNode payload 处理依赖现有 Kafka deserializer。如现有 outbox dispatch 用 Map<String,Object> 而非 JsonNode，按现有模式调整 payload 解析（建议在 implementation 时 Read 一个现有 `*EventConsumer.java` 对齐风格）。

- [ ] **Step 2: 验证编译**

```bash
mvn -pl falconx-trading-core-service compile -o -DskipTests 2>&1 | tail -3
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add falconx-trading-core-service/src/main/java/com/falconx/trading/consumer/TradingPositionClosePostProcessConsumer.java
git commit -m "feat(trading-consumer): TradingPositionClosePostProcessConsumer

Sprint 3 S6 Task 4：自产自销 outbox 消费者。

- @KafkaListener falconx.trading.position.close.post-process
- 按 eventType 分发：TRADE_WRITE / LIQUIDATION_LOG_WRITE /
  PENDING_ORDER_CASCADE_CANCEL
- 每个 sub-handler 独立 @Transactional，失败 throw 由 Spring Kafka
  retry / DLT 兜底
- TRADE_WRITE 调 saveWithExplicitId（Task 1 加的方法）保证 id 与 in-memory
  对象一致"
```

---

## Task 5: 单元测试 — 主事务 + 消费者

**Files:**
- Create: `falconx-trading-core-service/src/test/java/com/falconx/trading/application/TradingPositionCloseApplicationServiceS6Tests.java`
- Create: `falconx-trading-core-service/src/test/java/com/falconx/trading/consumer/TradingPositionClosePostProcessConsumerTests.java`

- [ ] **Step 1: 主事务拆分测试**

```java
package com.falconx.trading.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionCloseReason;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.repository.TradingLiquidationLogRepository;
import com.falconx.trading.repository.TradingOutboxRepository;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.TradingTradeRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TradingPositionCloseApplicationServiceS6Tests {

    @Test
    void asyncEnabled_manualClose_mainTxOnlyWritesAccountPositionExposureOutbox() {
        // 构造 mock + 启用 async-post-process
        TradingTradeRepository tradeRepo = mock(TradingTradeRepository.class);
        TradingLiquidationLogRepository liquidationLogRepo = mock(TradingLiquidationLogRepository.class);
        TradingOutboxRepository outboxRepo = mock(TradingOutboxRepository.class);
        TradingPendingOrderTriggerRepository pendingRepo = mock(TradingPendingOrderTriggerRepository.class);
        // 其他依赖 mock（按实际构造器签名补齐）

        // ApplyService 构造时传 asyncPostProcessEnabled=true
        // ... (按现有构造器签名调整)

        // 触发手动平仓 → 调 settlePositionExit
        // ...

        // 断言：
        // 1. tradeRepo.save() never 被调（异步路径不直接写 t_trade）
        verify(tradeRepo, never()).save(any(TradingTrade.class));
        // 2. liquidationLogRepo.save() never 被调（手动平仓不强平）
        verify(liquidationLogRepo, never()).save(any());
        // 3. pendingRepo.cancelAllSlTpByPositionId() never 被调
        verify(pendingRepo, never()).cancelAllSlTpByPositionId(any(), any());
        // 4. outboxRepo.save() 被调 2 次：主事件 position.closed + post-process TRADE_WRITE
        verify(outboxRepo, times(2)).save(any());
    }

    @Test
    void asyncEnabled_liquidation_outboxIncludesLiquidationLogWrite() {
        // ... 触发强平 + asyncEnabled=true
        // 断言 outboxRepo.save() 调用 3 次：position.liquidated + TRADE_WRITE + LIQUIDATION_LOG_WRITE
    }

    @Test
    void asyncEnabled_positionWithSlTp_outboxIncludesPendingOrderCascadeCancel() {
        // ... 触发持仓平仓 + 该持仓有关联 SL/TP 挂单
        // 断言 outboxRepo.save() 调用 3 次：position.closed + TRADE_WRITE + PENDING_ORDER_CASCADE_CANCEL
    }

    @Test
    void asyncDisabled_fallbackSyncPath_writesAllTablesInMainTx() {
        // ... 设置 asyncEnabled=false
        // 断言 tradeRepo.save() / liquidationLogRepo.save() / pendingRepo.cancel() 都被调用
        // outboxRepo.save() 只被调 1 次（主事件）
    }

    @Test
    void reservedTradeId_appearsInBothInMemoryTradeAndOutboxPayload() {
        // 构造 idGenerator mock 返回固定 id 5001L
        // 触发 manual close
        // 断言：
        // 1. PositionCloseResult.trade().tradeId() == 5001L
        // 2. outboxRepo.save 接受的 TRADE_WRITE event 的 payload.tradeId == 5001L
        ArgumentCaptor<com.falconx.trading.entity.TradingOutboxMessage> captor =
                ArgumentCaptor.forClass(com.falconx.trading.entity.TradingOutboxMessage.class);
        verify(outboxRepo, atLeastOnce()).save(captor.capture());
        // 找到 eventType=TRADE_WRITE 的那条，断言 payload.get("tradeId").equals(5001L)
    }
}
```

注：测试骨架已给出关键断言，**实现 Task 5 时按 TradingPositionCloseApplicationService 现有构造器补齐其他 mock 依赖**。

- [ ] **Step 2: 消费者测试**

```java
package com.falconx.trading.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.repository.TradingLiquidationLogRepository;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.TradingTradeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class TradingPositionClosePostProcessConsumerTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void handleTradeWrite_callsSaveWithExplicitIdUsingPayloadTradeId() throws Exception {
        TradingTradeRepository tradeRepo = mock(TradingTradeRepository.class);
        TradingLiquidationLogRepository liqRepo = mock(TradingLiquidationLogRepository.class);
        TradingPendingOrderTriggerRepository pendingRepo = mock(TradingPendingOrderTriggerRepository.class);

        TradingPositionClosePostProcessConsumer consumer =
                new TradingPositionClosePostProcessConsumer(tradeRepo, liqRepo, pendingRepo);

        JsonNode payload = MAPPER.readTree("""
                {
                  "eventType": "TRADE_WRITE",
                  "tradeId": 5001,
                  "orderId": 100,
                  "positionId": 200,
                  "userId": 7,
                  "symbol": "EURUSD",
                  "side": "BUY",
                  "tradeType": "CLOSE",
                  "quantity": "0.01",
                  "price": "1.10000000",
                  "fee": "0.00000000",
                  "realizedPnl": "0.50000000",
                  "tradedAt": "2026-05-26T10:00:00Z"
                }
                """);

        consumer.consume("event-1", payload);

        ArgumentCaptor<TradingTrade> tradeCaptor = ArgumentCaptor.forClass(TradingTrade.class);
        verify(tradeRepo).saveWithExplicitId(tradeCaptor.capture());
        assertEquals(5001L, tradeCaptor.getValue().tradeId());
        assertEquals("EURUSD", tradeCaptor.getValue().symbol());
    }

    @Test
    void handlePendingOrderCascadeCancel_callsRepoWithFormattedReason() throws Exception {
        // 类似 happy path 测试 PENDING_ORDER_CASCADE_CANCEL
    }

    @Test
    void unknownEventType_logsWarnAndDoesNotThrow() throws Exception {
        // payload.eventType = "UNKNOWN_FOO"
        // 期望：consumer.consume 不抛异常，且所有 repo 都 never 被调用
    }
}
```

- [ ] **Step 3: 运行所有 S6 单元测试**

```bash
mvn -pl falconx-trading-core-service -am test -o \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='TradingPositionCloseApplicationServiceS6Tests,TradingPositionClosePostProcessConsumerTests' 2>&1 | tail -10
```

Expected: 所有测试通过。

- [ ] **Step 4: Commit**

```bash
git add falconx-trading-core-service/src/test/java/com/falconx/trading/application/TradingPositionCloseApplicationServiceS6Tests.java \
        falconx-trading-core-service/src/test/java/com/falconx/trading/consumer/TradingPositionClosePostProcessConsumerTests.java
git commit -m "test(trading-position-close): S6 主事务拆分 + 消费者单测

Sprint 3 S6 Task 5。

主事务测试：
- async=true 手动平仓主事务不写 t_trade/t_liquidation_log/cascade
- async=true 强平 outbox 含 LIQUIDATION_LOG_WRITE
- async=true 含 SL/TP outbox 含 PENDING_ORDER_CASCADE_CANCEL
- async=false 回退路径所有表都在主事务
- reserved tradeId 一致性 in-memory ↔ outbox payload

消费者测试：
- TRADE_WRITE 调 saveWithExplicitId
- PENDING_ORDER_CASCADE_CANCEL 调 cancelAllSlTpByPositionId
- 未知 eventType 仅 warn"
```

---

## Task 6: 集成 sanity 脚本

**Files:**
- Create: `scripts/s6-sanity-check.sh`

- [ ] **Step 1: 新建脚本**

```bash
#!/usr/bin/env bash
# Sprint 3 S6 sanity：手动平仓 → 5s 后 t_trade 落盘 → 整体最终一致
# 用法：bash scripts/s6-sanity-check.sh [POSITION_ID]
set -euo pipefail

POSITION_ID="${1:?usage: s6-sanity-check.sh <POSITION_ID>}"

echo "=== 1. 平仓前 t_position / t_trade 状态 ==="
docker exec falconx-mysql mysql -uroot -proot -e "
  SELECT id, user_id, symbol, status, close_reason FROM falconx_trading.t_position WHERE id = ${POSITION_ID};
  SELECT id, position_id, trade_type FROM falconx_trading.t_trade WHERE position_id = ${POSITION_ID};
" 2>&1 | grep -v 'Warning\|mysql:'

USER_ID=$(docker exec falconx-mysql mysql -uroot -proot -BN -e "SELECT user_id FROM falconx_trading.t_position WHERE id = ${POSITION_ID};" 2>/dev/null)

echo
echo "=== 2. 调平仓 API ==="
curl -s -X POST -H "Content-Type: application/json" \
  -d "{\"userId\": ${USER_ID}, \"positionId\": ${POSITION_ID}}" \
  "http://localhost:18080/api/v1/trading/positions/close" | jq .

echo
echo "=== 3. 主事务即时检查（应已 commit）==="
docker exec falconx-mysql mysql -uroot -proot -e "
  SELECT id, status, close_reason, close_price, realized_pnl FROM falconx_trading.t_position WHERE id = ${POSITION_ID};
  SELECT COUNT(*) AS outbox_post_process_count FROM falconx_trading.t_outbox
    WHERE event_type = 'trading.position.close.post-process' AND event_id LIKE '%${POSITION_ID}%';
" 2>&1 | grep -v 'Warning\|mysql:'

echo
echo "=== 4. 等 5s 让 outbox 消费完成 ==="
sleep 5

echo
echo "=== 5. 5s 后 t_trade / 级联 SL/TP 状态 ==="
docker exec falconx-mysql mysql -uroot -proot -e "
  SELECT id, position_id, trade_type, realized_pnl FROM falconx_trading.t_trade WHERE position_id = ${POSITION_ID};
  SELECT id, position_id, status FROM falconx_trading.t_pending_order_trigger WHERE position_id = ${POSITION_ID};
  SELECT id, status, sent_at FROM falconx_trading.t_outbox
    WHERE event_type = 'trading.position.close.post-process' AND event_id LIKE '%${POSITION_ID}%';
" 2>&1 | grep -v 'Warning\|mysql:'

echo
echo "=== 6. 期望 ==="
echo "  - t_trade 应有一条 trade_type=CLOSE 的记录"
echo "  - 关联 SL/TP 挂单 status=CANCELED"
echo "  - outbox post-process 行 status=SENT，sent_at 非 null"
```

- [ ] **Step 2: 加可执行权限 + Commit**

```bash
chmod +x scripts/s6-sanity-check.sh
git add scripts/s6-sanity-check.sh
git commit -m "test(trading-position-close): S6 sanity 脚本

下一笔手动平仓 → 主事务即时检查 → 5s 等异步消费 → t_trade /
级联 SL/TP / outbox SENT 全部验证。"
```

---

## Task 7: 全测试套件回归

- [ ] **Step 1: 跑 trading-core 全部测试**

```bash
mvn -pl falconx-trading-core-service -am test -o -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee /tmp/s6-test-results.log | tail -15
```

Expected: 仅预存 flaky `TradingKafkaMarketEventIntegrationTests` 失败（与 S6 无关），其余全过。

如果有新失败：

```bash
grep -lE "Failures: [1-9]|Errors: [1-9]" falconx-trading-core-service/target/surefire-reports/*.txt
```

逐一排查。常见原因：

1. 现有 `TradingPositionCloseApplicationServiceTests` 测试可能直接 assert `tradeRepo.save` 被调 — 需要根据 asyncEnabled 区分期望
2. Outbox dispatcher 集成测试可能受新 event_type 影响

- [ ] **Step 2: Commit（如有测试调整）**

```bash
git commit -m "test(trading-position-close): 调现有 S6 相关测试以适配 async 路径"
```

---

## Task 8: 部署 demo（**前置：C1 部署 48h 稳定**）

**前置条件检查：**

1. C1 已部署 demo 服务器（git log origin/main 含 C1 实施 commit）
2. 距 C1 部署 ≥ 48 小时
3. C1 期间监控指标无回归（`Innodb_row_lock_waits` 增量符合预期、无 5xx 异常）

只有以上 3 项满足才能继续 Task 8。**否则 PAUSE，写入运维 log 等 C1 稳定**。

- [ ] **Step 1: mvn package**

```bash
mvn -pl falconx-trading-core-service -am package -Dmaven.test.skip=true -o 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 2: 记录部署前基线**

```bash
ssh ubuntu@10.143.170.189 "
  docker exec falconx-mysql mysql -uroot -proot -e \"
    SHOW STATUS LIKE 'Innodb_row_lock%';
    SELECT COUNT(*) AS t_trade_count, MAX(traded_at) AS latest FROM falconx_trading.t_trade;
    SELECT COUNT(*) AS t_outbox_pending FROM falconx_trading.t_outbox WHERE status != 2;
  \" 2>&1 | grep -v 'Warning\\|mysql:'
" | tee /tmp/s6-pre-deploy-baseline.txt
```

- [ ] **Step 3: 部署**

```bash
bash scripts/deploy-to-server.sh -s trading-core-service --skip-mvn 2>&1 | tail -10
```

- [ ] **Step 4: 部署后 5 分钟验证**

```bash
sleep 300
ssh ubuntu@10.143.170.189 "
  echo '[trading-core 启动日志]'
  docker compose -f /home/ubuntu/falconx/docker-compose.prod.yml logs --no-color --since 5m trading-core-service 2>&1 | grep -E 'Started.*Application|position.close.post-process|ERROR' | head -10
  echo
  echo '[Kafka consumer group position-close-post-process]'
  docker exec falconx-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group falconx.trading-core-service.position-close-post-process --describe 2>&1 | tail -5
  echo
  echo '[/actuator/health]'
  curl -s --max-time 5 -w 'http=%{http_code}\n' http://localhost:18083/actuator/health
"
```

Expected：

- 启动日志含 `Started TradingCoreServiceApplication`
- Kafka consumer group 已注册（即使无消息也应显示）
- health = 200

- [ ] **Step 5: 手动平仓 sanity 验证（生产数据）**

挑一个 demo 测试账户的小仓位（约定的 dry-run 持仓 ID），用 sanity 脚本验证完整链路：

```bash
ssh ubuntu@10.143.170.189 "cd /home/ubuntu/falconx && bash scripts/s6-sanity-check.sh <test-position-id>"
```

Expected：5s 后 t_trade 出现该 position 的 CLOSE 记录。

- [ ] **Step 6: 监控 24/48h**

部署 24h + 48h 后：

```bash
ssh ubuntu@10.143.170.189 "docker exec falconx-mysql mysql -uroot -proot -e \"
  SELECT COUNT(*) AS recent_position_closes_24h FROM falconx_trading.t_position
    WHERE status IN (3,4) AND updated_at > NOW() - INTERVAL 24 HOUR;
  SELECT COUNT(*) AS recent_trades_24h FROM falconx_trading.t_trade
    WHERE traded_at > NOW() - INTERVAL 24 HOUR;
  SELECT status, COUNT(*) FROM falconx_trading.t_outbox
    WHERE event_type = 'trading.position.close.post-process' AND created_at > NOW() - INTERVAL 24 HOUR
    GROUP BY status;
  SHOW STATUS LIKE 'Innodb_row_lock%';
\" 2>&1 | grep -v 'Warning\\|mysql:'" | tee /tmp/s6-post-deploy-24h.txt
```

Expected 24h 后：

- `recent_trades_24h ≈ recent_position_closes_24h`（每笔平仓最终都写 1 条 trade）
- outbox post-process status 全部 SENT(2)，无 FAILED(3/4)
- `Innodb_row_lock_waits` 增量与平仓 TPS 成正比，相比 C1 时基线进一步下降 ~33%

- [ ] **Step 7: 更新性能报告 §0.7 + Sprint 3 完成**

部署 48h 验证通过后，在性能报告中加 Sprint 3 实测数据 + S5 是否仍需要的复评估。

```bash
git add docs/perf/性能分析-2026-05-25.md
git commit -m "docs(perf): Sprint 3 C1+S6 部署 48h 实测数据"
git push origin main
```

---

## Self-Review

**Spec coverage** — 对照 S6 design spec §1-§11：

| Spec 章节 | 覆盖 Task |
|---|---|
| §1 背景 | Task 8 实测验证 |
| §2 当前实现 7 张表 | Task 3 主事务拆分 |
| §3.1 拆分原则（保留 vs 异步） | Task 3 |
| §3.2 方案 B（已 approve） | Task 3 异步路径实现 |
| §3.3.1 新增 outbox event payload | Task 3 buildXxxOutbox 方法 |
| §3.3.2 主事务保留 5 项 | Task 3 |
| §3.3.3 outbox 消费者 | Task 4 |
| §3.3.4 tradeId 提前 reserve | Task 3 + Task 1 saveWithExplicitId |
| §3.4 一致性保证（监控） | Task 6 sanity + Task 8 监控 |
| §3.5 调用方影响（PositionCloseResult 不变） | Task 3 in-memory trade 保留 |
| §3.6 兼容性 | Task 3 + Task 2 feature flag |
| §4 失败语义 | Task 5 单测 |
| §5 测试矩阵 8 项 | Task 5 |
| §6.1 风险（含 feature flag） | Task 2 + Task 3 同步回退分支 |
| §6.2 回滚 | feature flag false 即回退；revert commit 即可 |
| §7 R6 验证清单 | Task 7 + Task 8 |
| §8 涉及范围 | 文件清单对齐 |
| §9 YAGNI | 不删除原同步分支（feature flag 控制） |

**Placeholder scan** — 全文 "TBD/TODO" 无。`...` 仅出现在测试骨架中"按现有构造器签名补齐其他 mock 依赖"——这是实现时必读项，已注明。

**Type consistency** — `TradingTrade` 构造器参数顺序在 Task 1 / Task 3 / Task 4 三处使用，必须以 entity 实际 record 字段为准（已注释提醒实现时 Read 一次）。

**Scope check** — 单一 sub-project；文件 ≤ 8；任务依赖图清晰。

---

## 不做（YAGNI 已明确）

- ❌ 不动 t_trade / t_position / t_liquidation_log schema
- ❌ 不引入 Saga / event sourcing 模式
- ❌ 不解决跨实例 outbox 顺序保证（独立工单）
- ❌ 不与 C1 同时部署（必须 C1 稳定 48h 后才上 S6）
- ❌ 不删除现有同步路径（feature flag 控制，1 周观察后删除）
