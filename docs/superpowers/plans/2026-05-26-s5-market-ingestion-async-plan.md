# S5 — MarketDataIngestionApplicationService 异步化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `MarketDataIngestionApplicationService.ingestPlatformQuote` 单 tick 链路中"可异步"的 4 个下游（ClickHouse 入队 / Kafka publishPriceTick / WebSocket 推 quote / WebSocket 推 active K 线 + finalized K 线 3 步）下沉到按 symbol 分片的独立 executor，主线程仅保留"必须同步"的 2 项（Redis writeLatestQuote / K 线聚合）。单 tick 主线程同步耗时从 4-12ms 降到 1-3ms（理论 -75%）。

**Architecture:** 新增 `MarketQuoteDownstreamDispatcher` —— 16 个 single-thread executor 分区池，按 `hash(symbol) mod 16` 分发任务，保证同 symbol FIFO；CallerRunsPolicy 队列满时退化为同步执行（不丢消息但拖慢主线程，Prometheus Gauge `market.ingestion.dispatcher.queue.size` 可观测）。`MarketDataIngestionApplicationService.ingestPlatformQuote` 内部按 `falconx.market.ingestion.async-downstream.enabled` feature flag 分支：true → 异步路径（dispatcher.dispatch 一次提交多个下游），false → 同步回退路径（保留现有代码逻辑）。每个异步下游独立 try-catch 实现异常隔离。

**Tech Stack:** Java 25 + Spring Boot 4 + Micrometer + ThreadPoolExecutor + LinkedBlockingQueue。

**Design spec:** [`docs/superpowers/specs/2026-05-26-s5-market-ingestion-async-design.md`](../specs/2026-05-26-s5-market-ingestion-async-design.md)

**Epic:** [`docs/superpowers/specs/2026-05-26-sprint-3-epic-overview.md`](../specs/2026-05-26-sprint-3-epic-overview.md)

**部署节奏：** S5 改 market-service，与 trading-core 的 C1/S6 解耦，可独立部署。建议在 C1+S6 demo 全部稳定后再上 S5，避免同时太多变量。

---

## File Structure

| 文件 | 用途 | Task |
|---|---|---|
| `falconx-market-service/src/main/java/com/falconx/market/application/MarketQuoteDownstreamDispatcher.java`（新） | symbol 分区 dispatcher + Prometheus Gauge + ThreadFactory | Task 1 |
| `falconx-market-service/src/test/java/com/falconx/market/application/MarketQuoteDownstreamDispatcherTests.java`（新） | dispatcher 单测 | Task 1 |
| `falconx-market-service/src/main/resources/application.yml` | 加 partition-count + queue-capacity + feature flag | Task 2 |
| `falconx-market-service/src/main/java/com/falconx/market/application/MarketDataIngestionApplicationService.java` | ingestPlatformQuote 主链路重构（异步路径 + 同步回退） | Task 3 |
| `falconx-market-service/src/test/java/com/falconx/market/application/MarketDataIngestionAsyncTests.java`（新） | 异步路径单测 | Task 4 |
| `scripts/s5-load-test.sh`（新） | 本地压测脚本验证 dispatcher 表现 | Task 5 |

任务依赖：`1 → 2 → 3 → 4 → 5 → 6 → 7`。

---

## Task 1: MarketQuoteDownstreamDispatcher 类 + 单元测试

**Files:**
- Create: `falconx-market-service/src/main/java/com/falconx/market/application/MarketQuoteDownstreamDispatcher.java`
- Create: `falconx-market-service/src/test/java/com/falconx/market/application/MarketQuoteDownstreamDispatcherTests.java`

- [ ] **Step 1: 写失败测试**

```java
package com.falconx.market.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MarketQuoteDownstreamDispatcherTests {

    private MarketQuoteDownstreamDispatcher dispatcher;

    @AfterEach
    void shutdown() throws InterruptedException {
        if (dispatcher != null) {
            dispatcher.shutdown();
            dispatcher.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void dispatch_taskExecutedOnPartitionThread() throws InterruptedException {
        dispatcher = new MarketQuoteDownstreamDispatcher(4, 100, asProvider(new SimpleMeterRegistry()));
        CountDownLatch latch = new CountDownLatch(1);
        ConcurrentHashMap<String, String> recordedThread = new ConcurrentHashMap<>();

        dispatcher.dispatch("EURUSD", () -> {
            recordedThread.put("thread", Thread.currentThread().getName());
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        String threadName = recordedThread.get("thread");
        assertTrue(threadName.startsWith("market-quote-downstream-"),
                "thread should be from dispatcher pool, got: " + threadName);
    }

    @Test
    void dispatch_sameSymbolAlwaysSamePartition() throws InterruptedException {
        dispatcher = new MarketQuoteDownstreamDispatcher(8, 1000, asProvider(new SimpleMeterRegistry()));
        Set<String> threads = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(50);

        for (int i = 0; i < 50; i++) {
            dispatcher.dispatch("EURUSD", () -> {
                threads.add(Thread.currentThread().getName());
                latch.countDown();
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, threads.size(),
                "all EURUSD tasks should run on same partition thread, got: " + threads);
    }

    @Test
    void dispatch_differentSymbolsCanGoToDifferentPartitions() throws InterruptedException {
        dispatcher = new MarketQuoteDownstreamDispatcher(16, 1000, asProvider(new SimpleMeterRegistry()));
        Set<String> threads = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(8);
        String[] symbols = {"AAPL", "GOOG", "TSLA", "MSFT", "AMZN", "META", "NVDA", "BRK.B"};

        for (String symbol : symbols) {
            dispatcher.dispatch(symbol, () -> {
                threads.add(Thread.currentThread().getName());
                latch.countDown();
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        // 8 个不同 symbol 极大概率落在不同 partition（hash 冲突仍可能但应该 > 1）
        assertTrue(threads.size() >= 2,
                "different symbols should span multiple partitions, got: " + threads);
    }

    @Test
    void dispatch_queueFull_callerRunsPolicyExecutesOnMainThread() throws InterruptedException {
        // 1 partition, queue cap 1, 提交 1 个长任务占住 worker + 1 个入队 + 第 3 个触发 CallerRuns
        dispatcher = new MarketQuoteDownstreamDispatcher(1, 1, asProvider(new SimpleMeterRegistry()));
        CountDownLatch blockingLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        ConcurrentHashMap<String, String> callerRunsThread = new ConcurrentHashMap<>();

        // Task 1: 长任务占住 worker
        dispatcher.dispatch("SYM", () -> {
            blockingLatch.countDown();
            try {
                releaseLatch.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        // 等 worker 开始跑
        assertTrue(blockingLatch.await(1, TimeUnit.SECONDS));

        // Task 2: 入队（queue cap = 1 已满）
        dispatcher.dispatch("SYM", () -> {});

        // Task 3: 触发 CallerRunsPolicy（在主线程执行）
        String mainThread = Thread.currentThread().getName();
        dispatcher.dispatch("SYM", () -> {
            callerRunsThread.put("runs-on", Thread.currentThread().getName());
        });

        assertEquals(mainThread, callerRunsThread.get("runs-on"),
                "CallerRunsPolicy should execute on caller thread when queue is full");

        // 释放 worker，让 dispatcher 能正常 shutdown
        releaseLatch.countDown();
    }

    @Test
    void queueSizeGauge_registeredPerPartition() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        dispatcher = new MarketQuoteDownstreamDispatcher(4, 100, asProvider(registry));

        long gaugeCount = registry.getMeters().stream()
                .filter(m -> "market.ingestion.dispatcher.queue.size".equals(m.getId().getName()))
                .count();
        assertEquals(4, gaugeCount, "expected 4 gauges (1 per partition)");
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<MeterRegistry> asProvider(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn -pl falconx-market-service -am test -o \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='MarketQuoteDownstreamDispatcherTests' 2>&1 | tail -8
```

Expected: BUILD FAILURE / `cannot find symbol: class MarketQuoteDownstreamDispatcher`

- [ ] **Step 3: 实现 MarketQuoteDownstreamDispatcher**

```java
package com.falconx.market.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 2026-05-26 Sprint 3 S5：market ingestion 异步化用的 symbol 分区 dispatcher。
 *
 * <p>按 {@code hash(symbol) mod partitionCount} 把任务分发到 N 个独立 single-thread
 * executor，保证同 symbol 任务严格 FIFO（同 partition 是单线程）；不同 symbol 可并行处理。
 *
 * <p>背压策略：{@link ThreadPoolExecutor.CallerRunsPolicy} —— 队列满时退化为
 * 提交线程同步执行任务，保证不丢消息但可能拖慢上游。Prometheus Gauge
 * {@code market.ingestion.dispatcher.queue.size} 按 partition 维度暴露，
 * 运维监控 > 5000 报警。
 *
 * <p>线程命名：{@code market-quote-downstream-<partition>-<seq>}。
 *
 * <p>生命周期：Spring 容器关闭时调 {@link #shutdown()} 优雅终止；
 * {@link #awaitTermination(long, TimeUnit)} 用于测试。
 */
@Component
public class MarketQuoteDownstreamDispatcher {

    private final ThreadPoolExecutor[] partitions;
    private final int partitionCount;

    public MarketQuoteDownstreamDispatcher(
            @Value("${falconx.market.ingestion.downstream-dispatcher.partition-count:16}") int partitionCount,
            @Value("${falconx.market.ingestion.downstream-dispatcher.queue-capacity:10000}") int queueCapacity,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.partitionCount = Math.max(1, partitionCount);
        this.partitions = new ThreadPoolExecutor[this.partitionCount];
        for (int i = 0; i < this.partitionCount; i++) {
            int partitionIndex = i;
            this.partitions[i] = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(queueCapacity),
                    new MarketQuoteDownstreamThreadFactory(partitionIndex),
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );
        }
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            for (int i = 0; i < this.partitionCount; i++) {
                int idx = i;
                Gauge.builder("market.ingestion.dispatcher.queue.size",
                              () -> (double) partitions[idx].getQueue().size())
                     .tag("partition", String.valueOf(idx))
                     .description("symbol 分区 dispatcher 队列深度，> 5000 应告警")
                     .register(registry);
            }
        }
    }

    /**
     * 按 symbol 分发任务到固定 partition。
     *
     * @param symbol 用于分片的 key，相同 symbol 始终走同一 partition
     * @param task 异步执行的任务
     */
    public void dispatch(String symbol, Runnable task) {
        int idx = Math.floorMod(symbol.hashCode(), partitionCount);
        partitions[idx].execute(task);
    }

    public void shutdown() {
        for (ThreadPoolExecutor partition : partitions) {
            partition.shutdown();
        }
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        for (ThreadPoolExecutor partition : partitions) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0 || !partition.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) {
                return false;
            }
        }
        return true;
    }

    public int partitionCount() {
        return partitionCount;
    }

    public int queuedTaskCount(int partitionIndex) {
        if (partitionIndex < 0 || partitionIndex >= partitionCount) {
            return 0;
        }
        return partitions[partitionIndex].getQueue().size();
    }

    private static final class MarketQuoteDownstreamThreadFactory implements ThreadFactory {

        private final int partitionIndex;
        private final AtomicInteger counter = new AtomicInteger();

        private MarketQuoteDownstreamThreadFactory(int partitionIndex) {
            this.partitionIndex = partitionIndex;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "market-quote-downstream-" + partitionIndex + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
mvn -pl falconx-market-service -am test -o \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='MarketQuoteDownstreamDispatcherTests' 2>&1 | tail -10
```

Expected: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: Commit**

```bash
git add falconx-market-service/src/main/java/com/falconx/market/application/MarketQuoteDownstreamDispatcher.java \
        falconx-market-service/src/test/java/com/falconx/market/application/MarketQuoteDownstreamDispatcherTests.java
git commit -m "feat(market-ingestion): MarketQuoteDownstreamDispatcher symbol 分区 dispatcher

Sprint 3 S5 Task 1。

- 16 个 single-thread executor，按 hash(symbol) mod 16 分发
- 同 symbol 严格 FIFO（同 partition 单线程）
- CallerRunsPolicy 队列满退化同步，不丢消息
- Prometheus Gauge market.ingestion.dispatcher.queue.size 按 partition
- 5 个单元测试覆盖 partition routing / FIFO 保证 / CallerRunsPolicy / Gauge 注册"
```

---

## Task 2: application.yml 加配置 + feature flag

**Files:**
- Modify: `falconx-market-service/src/main/resources/application.yml`

- [ ] **Step 1: 在 `falconx.market` 段下加配置**

定位现有 `falconx.market` yml 段（如 `analytics` / `kafka` 等同级），追加：

```yaml
falconx:
  market:
    # 现有配置...
    ingestion:
      # S5（Sprint 3 / 性能报告 §4 P0）：true=异步下游（ClickHouse + Kafka +
      # WebSocket + K 线 finalized 三步走 dispatcher 分片异步），false=回退同步。
      # 默认 true；问题排查时设 false 1 周观察。
      async-downstream:
        enabled: true
      downstream-dispatcher:
        # 16 partition：CPU 16 核以下均能利用；如 demo 服务器 8 核可降到 8
        partition-count: 16
        # 每 partition 10000 任务缓冲，按 600 q/s / 16 = 37 q/s/partition，10000 缓冲 ~270s
        queue-capacity: 10000
```

注意：缩进与现有 yml 对齐；如 `falconx.market` 段已存在则在其下追加，不要重复顶层 `falconx:`。

- [ ] **Step 2: 验证 yml 解析**

```bash
mvn -pl falconx-market-service compile -o -DskipTests 2>&1 | tail -3
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add falconx-market-service/src/main/resources/application.yml
git commit -m "config(market-ingestion): 加 S5 async-downstream feature flag + dispatcher 配置

Sprint 3 S5 Task 2。

- falconx.market.ingestion.async-downstream.enabled: true（默认启用异步）
- falconx.market.ingestion.downstream-dispatcher.partition-count: 16
- falconx.market.ingestion.downstream-dispatcher.queue-capacity: 10000"
```

---

## Task 3: MarketDataIngestionApplicationService 主链路改造

**Files:**
- Modify: `falconx-market-service/src/main/java/com/falconx/market/application/MarketDataIngestionApplicationService.java`

- [ ] **Step 1: 注入 dispatcher + feature flag**

修改 class fields 段，加：

```java
    private final MarketQuoteDownstreamDispatcher quoteDownstreamDispatcher;
    private final boolean asyncDownstreamEnabled;
```

修改构造器：

```java
    public MarketDataIngestionApplicationService(QuoteStandardizationService quoteStandardizationService,
                                                 MarketQuoteCacheWriter marketQuoteCacheWriter,
                                                 MarketAnalyticsWriter marketAnalyticsWriter,
                                                 MarketEventPublisher marketEventPublisher,
                                                 KlineAggregationService klineAggregationService,
                                                 MarketWebSocketPushService marketWebSocketPushService,
                                                 MarketQuoteQualityGuardService marketQuoteQualityGuardService,
                                                 MarketTradingScheduleGuardService marketTradingScheduleGuardService,
                                                 MarketQuoteMappingService marketQuoteMappingService,
                                                 MarketQuoteDownstreamDispatcher quoteDownstreamDispatcher,
                                                 @org.springframework.beans.factory.annotation.Value("${falconx.market.ingestion.async-downstream.enabled:true}") boolean asyncDownstreamEnabled) {
        this.quoteStandardizationService = quoteStandardizationService;
        this.marketQuoteCacheWriter = marketQuoteCacheWriter;
        this.marketAnalyticsWriter = marketAnalyticsWriter;
        this.marketEventPublisher = marketEventPublisher;
        this.klineAggregationService = klineAggregationService;
        this.marketWebSocketPushService = marketWebSocketPushService;
        this.marketQuoteQualityGuardService = marketQuoteQualityGuardService;
        this.marketTradingScheduleGuardService = marketTradingScheduleGuardService;
        this.marketQuoteMappingService = marketQuoteMappingService;
        this.quoteDownstreamDispatcher = quoteDownstreamDispatcher;
        this.asyncDownstreamEnabled = asyncDownstreamEnabled;
    }
```

- [ ] **Step 2: 重写 ingestPlatformQuote 内部下游链路**

找到现有代码段（行 140-160 范围，executable 分支的下游调用）：

```java
        marketQuoteCacheWriter.writeLatestQuote(standardQuote);
        marketAnalyticsWriter.writeQuoteTick(standardQuote);
        marketEventPublisher.publishPriceTick(toPriceTickPayload(standardQuote));
        marketWebSocketPushService.publishQuote(standardQuote);

        KlineAggregationResult aggregationResult = klineAggregationService.onQuote(standardQuote);
        aggregationResult.activeSnapshots().forEach(marketWebSocketPushService::publishKline);
        List<KlineSnapshot> finalizedSnapshots = aggregationResult.finalizedSnapshots();
        finalizedSnapshots.forEach(snapshot -> {
            marketEventPublisher.publishKlineUpdate(toKlinePayload(snapshot));
            marketAnalyticsWriter.writeKline(snapshot);
            marketWebSocketPushService.publishKline(snapshot);
        });
```

**替换为：**

```java
        // ===== 同步链路（必须）：Redis 写 + K 线状态机推进 =====
        // 这两步是其他业务的同读依赖（trading-core 同读 quote / chart 取活动 K 线快照），
        // 必须在 ingest() 返回前完成。
        marketQuoteCacheWriter.writeLatestQuote(standardQuote);
        KlineAggregationResult aggregationResult = klineAggregationService.onQuote(standardQuote);
        List<KlineSnapshot> finalizedSnapshots = aggregationResult.finalizedSnapshots();

        if (asyncDownstreamEnabled) {
            // ===== 异步链路（S5 / 性能报告 §4 P0）：按 symbol 分区 dispatcher =====
            // 同 symbol 任务严格 FIFO；不同 symbol 并行；CallerRunsPolicy 背压保证不丢。
            // 每个下游独立 try-catch：任一失败仅 warn，不影响其他下游。
            String symbol = standardQuote.symbol();
            quoteDownstreamDispatcher.dispatch(symbol, () -> dispatchDownstreamAsync(standardQuote, aggregationResult, finalizedSnapshots));
        } else {
            // ===== 同步回退路径（feature flag = false）=====
            marketAnalyticsWriter.writeQuoteTick(standardQuote);
            marketEventPublisher.publishPriceTick(toPriceTickPayload(standardQuote));
            marketWebSocketPushService.publishQuote(standardQuote);
            aggregationResult.activeSnapshots().forEach(marketWebSocketPushService::publishKline);
            finalizedSnapshots.forEach(snapshot -> {
                marketEventPublisher.publishKlineUpdate(toKlinePayload(snapshot));
                marketAnalyticsWriter.writeKline(snapshot);
                marketWebSocketPushService.publishKline(snapshot);
            });
        }
```

- [ ] **Step 3: 加 `dispatchDownstreamAsync` 私有方法**

在 class 底部加：

```java
    /**
     * 异步执行 ingestPlatformQuote 的 4 类下游：ClickHouse 入队 + Kafka publish +
     * WebSocket 推 quote/active K 线 + finalized K 线 3 步。
     *
     * <p>每个下游独立 try-catch 实现异常隔离：任一失败仅 warn 不重抛，
     * 其他下游继续执行。Kafka send 失败由 Spring Kafka producer 内部 retry 兜底；
     * ClickHouse 失败由 MybatisClickHouseMarketAnalyticsWriter 自身处理；
     * WebSocket 失败由前端重新订阅 + stale scan 兜底。
     *
     * <p>本方法在 {@link MarketQuoteDownstreamDispatcher} 的 worker 线程上执行（同 symbol
     * 严格 FIFO 由 dispatcher 保证）。
     */
    private void dispatchDownstreamAsync(StandardQuote quote,
                                         KlineAggregationResult aggregationResult,
                                         List<KlineSnapshot> finalizedSnapshots) {
        try {
            marketAnalyticsWriter.writeQuoteTick(quote);
        } catch (RuntimeException ex) {
            log.warn("market.ingestion.async.clickhouse-tick.failed symbol={} reason={}",
                    quote.symbol(), ex.toString());
        }
        try {
            marketEventPublisher.publishPriceTick(toPriceTickPayload(quote));
        } catch (RuntimeException ex) {
            log.warn("market.ingestion.async.kafka-price-tick.failed symbol={} reason={}",
                    quote.symbol(), ex.toString());
        }
        try {
            marketWebSocketPushService.publishQuote(quote);
        } catch (RuntimeException ex) {
            log.warn("market.ingestion.async.ws-quote.failed symbol={} reason={}",
                    quote.symbol(), ex.toString());
        }
        try {
            aggregationResult.activeSnapshots().forEach(marketWebSocketPushService::publishKline);
        } catch (RuntimeException ex) {
            log.warn("market.ingestion.async.ws-active-kline.failed symbol={} reason={}",
                    quote.symbol(), ex.toString());
        }
        for (KlineSnapshot snapshot : finalizedSnapshots) {
            try {
                marketEventPublisher.publishKlineUpdate(toKlinePayload(snapshot));
            } catch (RuntimeException ex) {
                log.warn("market.ingestion.async.kafka-kline-update.failed symbol={} interval={} reason={}",
                        snapshot.symbol(), snapshot.interval(), ex.toString());
            }
            try {
                marketAnalyticsWriter.writeKline(snapshot);
            } catch (RuntimeException ex) {
                log.warn("market.ingestion.async.clickhouse-kline.failed symbol={} interval={} reason={}",
                        snapshot.symbol(), snapshot.interval(), ex.toString());
            }
            try {
                marketWebSocketPushService.publishKline(snapshot);
            } catch (RuntimeException ex) {
                log.warn("market.ingestion.async.ws-finalized-kline.failed symbol={} interval={} reason={}",
                        snapshot.symbol(), snapshot.interval(), ex.toString());
            }
        }
    }
```

注：`KlineSnapshot.interval()` 字段名按现有 entity 实际签名；如不存在用 `.timeframe()` 或类似。**实现时先 Read `KlineSnapshot.java` 确认**。

- [ ] **Step 4: 验证编译**

```bash
mvn -pl falconx-market-service compile -o -DskipTests 2>&1 | tail -3
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add falconx-market-service/src/main/java/com/falconx/market/application/MarketDataIngestionApplicationService.java
git commit -m "refactor(market-ingestion): ingestPlatformQuote 主链路异步化（S5 Task 3）

Sprint 3 S5。

- 同步保留：marketQuoteCacheWriter.writeLatestQuote + klineAggregationService.onQuote
- 异步下沉：ClickHouse + Kafka + WebSocket 推送 + K 线 finalized 3 步
- 按 symbol hash 分区 → 同 symbol FIFO
- 每个下游独立 try-catch 异常隔离

feature flag falconx.market.ingestion.async-downstream.enabled=true 默认开。
设 false 走原同步路径回退。

Kafka topic / payload 不变；trading-core 消费顺序保持（按 symbol partition 顺序）。"
```

---

## Task 4: 单元测试 — ingestion async/sync 路径

**Files:**
- Create: `falconx-market-service/src/test/java/com/falconx/market/application/MarketDataIngestionAsyncTests.java`

- [ ] **Step 1: 新建测试类**

```java
package com.falconx.market.application;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.market.analytics.MarketAnalyticsWriter;
import com.falconx.market.cache.MarketQuoteCacheWriter;
import com.falconx.market.entity.KlineAggregationResult;
import com.falconx.market.entity.MarketQuoteQualityStatus;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.producer.MarketEventPublisher;
import com.falconx.market.provider.ExternalRawQuote;
import com.falconx.market.service.KlineAggregationService;
import com.falconx.market.service.MarketQuoteMappingService;
import com.falconx.market.service.MarketQuoteQualityGuardService;
import com.falconx.market.service.MarketTradingScheduleGuardService;
import com.falconx.market.service.QuoteStandardizationService;
import com.falconx.market.websocket.MarketWebSocketPushService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketDataIngestionAsyncTests {

    @Test
    void asyncEnabled_dispatchesDownstreamToDispatcher() {
        // 构造 mock 全套依赖
        QuoteStandardizationService standardize = mock(QuoteStandardizationService.class);
        MarketQuoteCacheWriter cache = mock(MarketQuoteCacheWriter.class);
        MarketAnalyticsWriter analytics = mock(MarketAnalyticsWriter.class);
        MarketEventPublisher publisher = mock(MarketEventPublisher.class);
        KlineAggregationService kline = mock(KlineAggregationService.class);
        MarketWebSocketPushService ws = mock(MarketWebSocketPushService.class);
        MarketQuoteQualityGuardService quality = mock(MarketQuoteQualityGuardService.class);
        MarketTradingScheduleGuardService schedule = mock(MarketTradingScheduleGuardService.class);
        MarketQuoteMappingService mapping = mock(MarketQuoteMappingService.class);
        MarketQuoteDownstreamDispatcher dispatcher = mock(MarketQuoteDownstreamDispatcher.class);

        // dispatcher.dispatch 配置为同步执行 task（测试用，方便断言）
        doAnswer(inv -> {
            Runnable task = inv.getArgument(1);
            task.run();
            return null;
        }).when(dispatcher).dispatch(any(), any());

        StandardQuote quote = newExecutableQuote("EURUSD");
        when(standardize.toStandardQuote(any())).thenReturn(quote);
        when(quality.evaluate(any())).thenReturn(quote);
        when(kline.onQuote(any())).thenReturn(new KlineAggregationResult(List.of(), List.of()));
        when(mapping.toPlatformQuote(any())).thenReturn(newExternalRawQuote());
        when(schedule.isTradingNow(any())).thenReturn(true);

        MarketDataIngestionApplicationService service = new MarketDataIngestionApplicationService(
                standardize, cache, analytics, publisher, kline, ws, quality, schedule, mapping,
                dispatcher, true);

        service.ingest(newExternalRawQuote());

        // 同步链路必须发生
        verify(cache, times(1)).writeLatestQuote(any());
        verify(kline, times(1)).onQuote(any());

        // 异步链路通过 dispatcher.dispatch 触发一次
        verify(dispatcher, atLeast(1)).dispatch(eq("EURUSD"), any());

        // dispatcher 异步执行后下游被调
        verify(analytics, times(1)).writeQuoteTick(any());
        verify(publisher, times(1)).publishPriceTick(any());
        verify(ws, times(1)).publishQuote(any());
    }

    @Test
    void asyncDisabled_fallbackToSyncPath_noDispatcherCall() {
        QuoteStandardizationService standardize = mock(QuoteStandardizationService.class);
        MarketQuoteCacheWriter cache = mock(MarketQuoteCacheWriter.class);
        MarketAnalyticsWriter analytics = mock(MarketAnalyticsWriter.class);
        MarketEventPublisher publisher = mock(MarketEventPublisher.class);
        KlineAggregationService kline = mock(KlineAggregationService.class);
        MarketWebSocketPushService ws = mock(MarketWebSocketPushService.class);
        MarketQuoteQualityGuardService quality = mock(MarketQuoteQualityGuardService.class);
        MarketTradingScheduleGuardService schedule = mock(MarketTradingScheduleGuardService.class);
        MarketQuoteMappingService mapping = mock(MarketQuoteMappingService.class);
        MarketQuoteDownstreamDispatcher dispatcher = mock(MarketQuoteDownstreamDispatcher.class);

        StandardQuote quote = newExecutableQuote("EURUSD");
        when(standardize.toStandardQuote(any())).thenReturn(quote);
        when(quality.evaluate(any())).thenReturn(quote);
        when(kline.onQuote(any())).thenReturn(new KlineAggregationResult(List.of(), List.of()));
        when(mapping.toPlatformQuote(any())).thenReturn(newExternalRawQuote());
        when(schedule.isTradingNow(any())).thenReturn(true);

        MarketDataIngestionApplicationService service = new MarketDataIngestionApplicationService(
                standardize, cache, analytics, publisher, kline, ws, quality, schedule, mapping,
                dispatcher, false);  // async=false 同步回退路径

        service.ingest(newExternalRawQuote());

        // dispatcher 不应被调用
        verify(dispatcher, never()).dispatch(any(), any());

        // 所有下游都在主线程同步执行
        verify(cache, times(1)).writeLatestQuote(any());
        verify(analytics, times(1)).writeQuoteTick(any());
        verify(publisher, times(1)).publishPriceTick(any());
        verify(ws, times(1)).publishQuote(any());
    }

    @Test
    void asyncEnabled_oneDownstreamFails_othersStillExecute() {
        QuoteStandardizationService standardize = mock(QuoteStandardizationService.class);
        MarketQuoteCacheWriter cache = mock(MarketQuoteCacheWriter.class);
        MarketAnalyticsWriter analytics = mock(MarketAnalyticsWriter.class);
        MarketEventPublisher publisher = mock(MarketEventPublisher.class);
        KlineAggregationService kline = mock(KlineAggregationService.class);
        MarketWebSocketPushService ws = mock(MarketWebSocketPushService.class);
        MarketQuoteQualityGuardService quality = mock(MarketQuoteQualityGuardService.class);
        MarketTradingScheduleGuardService schedule = mock(MarketTradingScheduleGuardService.class);
        MarketQuoteMappingService mapping = mock(MarketQuoteMappingService.class);
        MarketQuoteDownstreamDispatcher dispatcher = mock(MarketQuoteDownstreamDispatcher.class);

        doAnswer(inv -> {
            Runnable task = inv.getArgument(1);
            task.run();
            return null;
        }).when(dispatcher).dispatch(any(), any());

        StandardQuote quote = newExecutableQuote("EURUSD");
        when(standardize.toStandardQuote(any())).thenReturn(quote);
        when(quality.evaluate(any())).thenReturn(quote);
        when(kline.onQuote(any())).thenReturn(new KlineAggregationResult(List.of(), List.of()));
        when(mapping.toPlatformQuote(any())).thenReturn(newExternalRawQuote());
        when(schedule.isTradingNow(any())).thenReturn(true);

        // ClickHouse 写抛异常
        doAnswer(inv -> { throw new RuntimeException("clickhouse-down"); })
                .when(analytics).writeQuoteTick(any());

        MarketDataIngestionApplicationService service = new MarketDataIngestionApplicationService(
                standardize, cache, analytics, publisher, kline, ws, quality, schedule, mapping,
                dispatcher, true);

        // ingest 不应抛异常
        StandardQuote result = service.ingest(newExternalRawQuote());
        assertNotNull(result);

        // ClickHouse 失败后，Kafka 和 WebSocket 仍被调用
        verify(publisher, times(1)).publishPriceTick(any());
        verify(ws, times(1)).publishQuote(any());
    }

    private static org.mockito.ArgumentMatcher<String> eqArg(String expected) {
        return argument -> expected.equals(argument);
    }

    // helper: 上面 verify(dispatcher).dispatch(eq("EURUSD"), any()) 用 mockito.eq
    @SuppressWarnings("unchecked")
    private static <T> T eq(T expected) {
        return org.mockito.ArgumentMatchers.eq(expected);
    }

    // ---- helpers ----

    private StandardQuote newExecutableQuote(String symbol) {
        return new StandardQuote(
                symbol,
                new BigDecimal("1.10000000"),
                new BigDecimal("1.10010000"),
                new BigDecimal("1.10005000"),
                new BigDecimal("1.10005000"),
                OffsetDateTime.now(),
                "test-source",
                false,
                MarketQuoteQualityStatus.FRESH,
                null
        );
    }

    private ExternalRawQuote newExternalRawQuote() {
        return new ExternalRawQuote(
                "EURUSD", "test-source",
                new BigDecimal("1.10000000"), new BigDecimal("1.10010000"),
                OffsetDateTime.now()
        );
    }
}
```

注：`StandardQuote` 和 `ExternalRawQuote` 构造器参数顺序必须以实际 entity record 字段为准。**实现时先 Read 一次确认**。

- [ ] **Step 2: 运行测试**

```bash
mvn -pl falconx-market-service -am test -o \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='MarketDataIngestionAsyncTests' 2>&1 | tail -10
```

Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 3: Commit**

```bash
git add falconx-market-service/src/test/java/com/falconx/market/application/MarketDataIngestionAsyncTests.java
git commit -m "test(market-ingestion): S5 异步路径 + 同步回退 + 异常隔离单测

Sprint 3 S5 Task 4。

- asyncEnabled: dispatcher.dispatch 被调，同步链路（cache + kline）必须发生，
  异步链路（analytics + publisher + ws）通过 dispatcher 触发
- asyncDisabled: dispatcher 不被调，所有下游在主线程同步
- ClickHouse 失败时 Kafka 和 WebSocket 仍执行（异常隔离）"
```

---

## Task 5: 集成 sanity 脚本（本地连 LP 验证 dispatcher queue）

**Files:**
- Create: `scripts/s5-load-test.sh`

- [ ] **Step 1: 新建脚本**

```bash
#!/usr/bin/env bash
# Sprint 3 S5 sanity：连 LP 5 分钟后，对比 dispatcher partition queue 分布 + market CPU
# 用法：bash scripts/s5-load-test.sh
set -euo pipefail

echo "=== 1. 前置：market-service 必须在跑 ==="
if ! curl -s --max-time 3 -f http://localhost:18082/actuator/health > /dev/null; then
    echo "✗ market-service 未在 18082 监听，请先启动"
    exit 1
fi

echo
echo "=== 2. 启动观测，连续 5 分钟采集 dispatcher queue size ==="
echo "时间, partition, queue_size" > /tmp/s5-dispatcher-queue.csv
for i in $(seq 1 60); do
    timestamp=$(date '+%H:%M:%S')
    curl -s http://localhost:18082/actuator/metrics/market.ingestion.dispatcher.queue.size 2>/dev/null \
        | jq -r '.availableTags // [] | .[] | select(.tag=="partition") | .values[]' 2>/dev/null \
        | while read partition; do
            queue_size=$(curl -s "http://localhost:18082/actuator/metrics/market.ingestion.dispatcher.queue.size?tag=partition:${partition}" 2>/dev/null \
                | jq -r '.measurements[0].value' 2>/dev/null)
            echo "${timestamp}, ${partition}, ${queue_size}" >> /tmp/s5-dispatcher-queue.csv
        done
    sleep 5  # 60 × 5s = 5 minutes
done

echo
echo "=== 3. 结果统计 ==="
echo "[Partition queue 高峰值]"
awk -F',' 'NR>1 {if ($3 > max[$2]) max[$2] = $3} END {for (p in max) print "partition", p, "max queue:", max[p]}' /tmp/s5-dispatcher-queue.csv

echo
echo "[Partition queue 平均值]"
awk -F',' 'NR>1 {sum[$2] += $3; count[$2]++} END {for (p in sum) printf "partition %s avg queue: %.2f\n", p, sum[p]/count[p]}' /tmp/s5-dispatcher-queue.csv

echo
echo "=== 4. 期望 ==="
echo "  - 16 partition 各自有数据（说明 hash 分布合理）"
echo "  - 平均 queue size < 100（说明消费速度跟得上）"
echo "  - 高峰 queue size < 5000（< 5000 不告警）"
echo "  - 任何 partition queue 持续 > 5000 → 调高 partition_count 或排查下游瓶颈"
```

- [ ] **Step 2: 加可执行权限 + Commit**

```bash
chmod +x scripts/s5-load-test.sh
git add scripts/s5-load-test.sh
git commit -m "test(market-ingestion): S5 sanity 脚本 — 5 分钟采集 partition queue 分布"
```

---

## Task 6: 全测试套件回归

- [ ] **Step 1: 跑 market-service 全部测试**

```bash
mvn -pl falconx-market-service -am test -o -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee /tmp/s5-test-results.log | tail -15
```

Expected: `BUILD SUCCESS`

如有失败：

```bash
grep -lE "Failures: [1-9]|Errors: [1-9]" falconx-market-service/target/surefire-reports/*.txt
```

逐一处理。常见原因：

1. 现有 `MarketDataIngestionApplicationServiceTests` 测试可能 verify 各下游被同步调用 — 需要根据 asyncEnabled 区分期望
2. 现有 service 构造器签名变了，其他测试可能 mock 构造时缺参数 — 修测试

- [ ] **Step 2: Commit（如有测试调整）**

```bash
git commit -m "test(market-ingestion): 适配 S5 构造器新参数"
```

---

## Task 7: 部署 demo + 监控

**部署节奏：** S5 改 market-service，与 trading-core 的 C1/S6 无依赖关系。建议在 C1+S6 全部完成且 demo 稳定 24-48h 后再上 S5，避免同时太多变量难以归因。

- [ ] **Step 1: mvn package**

```bash
mvn -pl falconx-market-service -am package -Dmaven.test.skip=true -o 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 2: 记录基线**

```bash
ssh ubuntu@10.143.170.189 "
docker stats --no-stream --format '{{.Name}}: CPU {{.CPUPerc}} Mem {{.MemUsage}}' | grep -E 'market|redis'
docker exec falconx-redis redis-cli INFO 2>&1 | grep -E '^(instantaneous_ops_per_sec):'
curl -s http://localhost:18082/actuator/metrics/market.ingestion.dispatcher.queue.size 2>/dev/null | jq '.measurements[0].value // \"not-deployed-yet\"'
" | tee /tmp/s5-pre-deploy-baseline.txt
```

- [ ] **Step 3: 部署**

```bash
bash scripts/deploy-to-server.sh -s market-service --skip-mvn 2>&1 | tail -10
```

- [ ] **Step 4: 部署后 5 分钟验证**

```bash
sleep 300
ssh ubuntu@10.143.170.189 "
echo '[market-service 启动 + Dispatcher Gauge 注册]'
docker compose -f /home/ubuntu/falconx/docker-compose.prod.yml logs --no-color --since 5m market-service 2>&1 | grep -E 'Started.*Application|market-quote-downstream|ERROR' | head -10
echo
echo '[Prometheus /actuator/prometheus 含 dispatcher queue gauge]'
curl -s --max-time 5 http://localhost:18082/actuator/prometheus 2>/dev/null | grep 'market_ingestion_dispatcher_queue_size' | head -5
echo
echo '[market-service CPU vs 基线]'
docker stats --no-stream --format 'market: CPU {{.CPUPerc}}' falconx-market-service
"
```

Expected:

- 启动日志含 `Started MarketServiceApplication`
- `/actuator/prometheus` 含 16 个 `market_ingestion_dispatcher_queue_size{partition="0"...15"}` Gauge
- market CPU 应低于基线（24-27% → 期望 < 20%）

- [ ] **Step 5: 跑 S5 sanity（生产环境）**

```bash
ssh ubuntu@10.143.170.189 "cd /home/ubuntu/falconx && bash scripts/s5-load-test.sh"
```

观察 5 分钟 partition queue 分布。Expected:

- 16 partition 全有数据（说明 hash 分布良好）
- 平均 queue < 100（消费跟得上）
- 高峰 < 5000

- [ ] **Step 6: 监控 24h**

```bash
ssh ubuntu@10.143.170.189 "
echo '[Redis CPU 趋势 — 期望进一步下降 / 或保持 7% 左右]'
docker stats --no-stream --format 'redis: CPU {{.CPUPerc}}' falconx-redis
docker exec falconx-redis redis-cli INFO 2>&1 | grep -E '^(instantaneous_ops_per_sec):'

echo
echo '[market CPU 趋势]'
docker stats --no-stream --format 'market: CPU {{.CPUPerc}} Mem {{.MemUsage}}' falconx-market-service

echo
echo '[Kafka producer / trading consumer lag]'
docker exec falconx-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --all-groups --describe 2>&1 | grep -E 'market\\.price\\.tick|market\\.kline\\.update' | head -10
"
```

Expected 24h 后：

- market CPU 下降（基线 24-27% → 期望 15-20%）
- Redis 不变（S5 不动 Redis）
- Kafka consumer lag 不应增加（异步化后 send 延迟略增但顺序保持）

- [ ] **Step 7: 更新性能报告 + Sprint 3 完成**

```bash
# 报告更新 + commit
git add docs/perf/性能分析-2026-05-25.md
git commit -m "docs(perf): Sprint 3 S5 部署 24h 实测数据 + Sprint 3 收官"
git push origin main
```

---

## Self-Review

**Spec coverage** — 对照 S5 design spec §1-§11：

| Spec 章节 | 覆盖 Task |
|---|---|
| §1 背景 / 收益预期 | Task 7 实测 |
| §2 当前 6+ 下游 | Task 3 主链路重构 |
| §3.1 拆分原则 | Task 3 同步 vs 异步分支 |
| §3.2 顺序保证策略 | Task 1 dispatcher 按 symbol hash |
| §3.3.1 主链路重构 | Task 3 |
| §3.3.2 dispatcher 实现 | Task 1 |
| §3.4 异常隔离 | Task 3 dispatchDownstreamAsync 每个 try-catch |
| §3.5 顺序保证证据 | Task 1 同 symbol 测试 |
| §3.6 兼容性 | Task 3 feature flag 同步回退 |
| §3.7 调用方影响 | 设计层面，无具体 Task |
| §4 失败语义 | Task 4 异常隔离测试 |
| §5 测试矩阵 8 项 | Task 1 + Task 4 共 5+3=8 测试 |
| §6.1 风险 | Task 2 feature flag 回退 |
| §6.2 回滚 | feature flag false 即回退 |
| §7 R6 验证清单 | Task 6 + Task 7 |
| §8 涉及范围 | 文件清单对齐 |
| §9 YAGNI | Task 3 不动 Redis 写策略 / 不引入 outbox |

**Placeholder scan** — 全文无 "TBD/TODO"；测试代码中"按实际 entity record 字段为准"是实现时必读项，已注明。

**Type consistency** — `StandardQuote` / `ExternalRawQuote` / `KlineAggregationResult` 构造器参数顺序在 Task 4 使用，需以实际 entity 为准（已注释提醒）。

**Scope check** — 单一 sub-project；文件 ≤ 6；任务依赖图清晰；不依赖 C1/S6 完成。

---

## 不做（YAGNI 已明确）

- ❌ 不动 Redis 写策略（同步必须保留）
- ❌ 不动 K 线聚合算法
- ❌ 不引入新中间件
- ❌ 不解决跨 symbol 顺序
- ❌ 不修 saveLastValid 8 次 put（独立工单 §3 P1）
- ❌ 不与 C1/S6 同时部署（建议错峰，便于问题归因）
