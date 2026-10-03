# S5 — MarketDataIngestionApplicationService 异步化设计

- Sprint 3 第 3 项
- Epic：[`2026-05-26-sprint-3-epic-overview.md`](./2026-05-26-sprint-3-epic-overview.md)
- 性能报告引用：[`docs/perf/性能分析-2026-05-25.md`](../../perf/性能分析-2026-05-25.md) §4 P0
- 状态：待审

## 1. 背景

**性能报告 §4 P0 原文**：

> **MarketDataIngestionApplicationService.ingestPlatformQuote 单链路 6+ 同步下游**
> - 文件：`MarketDataIngestionApplicationService.java:140-160`
> - 现象：每 tick 顺序调 writeLatestQuote + writeQuoteTick + publishPriceTick + publishQuote + klineAggregation + 3 publishKline
> - 影响：单条 quote 链路 ≥ 6 次方法调用，任意环节阻塞拖全链
> - 推荐修复：Redis 写 + Kafka 发 + ClickHouse 入队异步化（线程池 + 队列），同步只保留 kline（数据依赖）

## 2. 当前实现细节

`MarketDataIngestionApplicationService.ingestPlatformQuote` 每 tick（demo 600 q/s）顺序执行：

| 序 | 下游 | 操作 | 同步必要性 | 当前耗时估算 |
|---|---|---|---|---|
| 1 | `marketQuoteCacheWriter.writeLatestQuote` | Redis 写最新报价（8 次 hash put + expire — 性能报告 §3 P1 待修） | **同步必须**：trading-core 下单同读 | ~1-3ms（Redis 8 RTT） |
| 2 | `marketAnalyticsWriter.writeQuoteTick` | ClickHouse 入队（内部已批量 flush 10s/200 batch） | 可异步 | ~50µs（仅入队） |
| 3 | `marketEventPublisher.publishPriceTick` | Kafka 写 `market.price.tick`（同步 send 单条） | 可异步 | ~3-8ms（Kafka send） |
| 4 | `marketWebSocketPushService.publishQuote` | 写 WebSocket session registry 内 push queue | 可异步 | ~100µs（内存写） |
| 5 | `klineAggregationService.onQuote` | 内存 K 线状态机推进（6 个 interval × ConcurrentHashMap.compute） | **同步必须**：数据依赖，活动 K 线快照需要立即返回 | ~100-300µs（内存计算） |
| 6 | active K 线 forEach `publishKline` | WebSocket 推 6 条活动 K 线 | 可异步 | ~600µs（6 × 内存写） |
| 7 | finalized K 线 forEach（每个新闭合 K 线）3 步：`publishKlineUpdate`（Kafka） + `writeKline`（ClickHouse 入队） + `publishKline`（WebSocket） | 仅 K 线收盘瞬间触发，多数 tick 不走 | 可异步 | ~3-8ms × N 个 finalized（仅收盘 tick） |

**关键瓶颈**：Kafka 同步 send（步骤 3 + 步骤 7 第一项）平均 3-8ms，**单 tick 链路 60% 时间在这里**。

**总单 tick 链路时间**（典型非收盘 tick）：

```
1 Redis (1-3ms) + 2 ClickHouse (50µs) + 3 Kafka (3-8ms) + 4 WS (100µs)
+ 5 Kline 计算 (100-300µs) + 6 WS×6 (600µs)
= 4-12ms 同步耗时
```

按 600 q/s × 平均 6ms = 3600ms/s = **CPU 占用 360%**（运行时观测 market CPU 实际 24-27%，说明实际 Kafka 同步时长更短，但量大累计 high）。

## 3. 设计

### 3.1 拆分原则

**同步必须保留**：

- 步骤 1 `writeLatestQuote`：trading-core 下单链路同读，stale 即可降级，但延迟必须 < 1s（同步是最稳路径）
- 步骤 5 `klineAggregationService.onQuote`：数据依赖，必须先推进 K 线状态机才能取活动快照

**可异步化（顺序保证 + 异常隔离）**：

- 步骤 2 `writeQuoteTick`（ClickHouse）：已经是入队模型，但当前从主线程入队，理论可改为提交到独立线程
- 步骤 3 `publishPriceTick`（Kafka）：**核心瓶颈**，改为内部 executor + 按 symbol 分片背压
- 步骤 4 `publishQuote`（WebSocket）：写 push queue，内存操作，但分支同步路径可下沉
- 步骤 6 K 线 active 推 WebSocket：同上
- 步骤 7 K 线 finalized 三步：可整体异步

### 3.2 顺序保证策略

**关键不变量**：同 symbol 的 quote 序列在下游必须保持原序（否则下游消费者可能看到价格回跳）。

**方案**：引入 `SymbolPartitionedQuoteDispatcher` —— 按 `hash(symbol) mod N` 分片到 N 个独立 single-thread executor，同 symbol 的 quote 始终在同一线程处理，**保证 per-symbol FIFO**。

参考 trading-core 已有的 `SymbolPartitionedPriceTickExecutor`（性能报告 §0.6 Sprint 1 5a7a21a 已暴露 backlog 监控）。本设计在 market-service 内复用相同模式。

### 3.3 改动方向

#### 3.3.1 主链路重构（伪代码）

```java
public StandardQuote ingest(...) {
    // ... existing validation / quoteQualityGuard ...

    if (!standardQuote.executable()) {
        // non-executable 路径：仅 Kafka publish，同步以保证事件顺序与可观察性
        marketEventPublisher.publishPriceTick(toPriceTickPayload(standardQuote));
        return standardQuote;
    }

    // ===== 同步链路（必须）=====
    marketQuoteCacheWriter.writeLatestQuote(standardQuote);                    // 1. Redis
    KlineAggregationResult aggregationResult = klineAggregationService.onQuote(standardQuote);  // 5. K 线状态机

    // ===== 异步链路（按 symbol 分片）=====
    quoteDownstreamDispatcher.dispatch(standardQuote.symbol(), () -> {
        // 步骤 2 / 3 / 4 / 6
        marketAnalyticsWriter.writeQuoteTick(standardQuote);                   // ClickHouse 入队
        marketEventPublisher.publishPriceTick(toPriceTickPayload(standardQuote));  // Kafka send
        marketWebSocketPushService.publishQuote(standardQuote);                // WebSocket
        aggregationResult.activeSnapshots().forEach(marketWebSocketPushService::publishKline);
    });

    // 步骤 7：finalized K 线（仅收盘 tick 走，量小）
    List<KlineSnapshot> finalizedSnapshots = aggregationResult.finalizedSnapshots();
    if (!finalizedSnapshots.isEmpty()) {
        quoteDownstreamDispatcher.dispatch(standardQuote.symbol(), () -> {
            finalizedSnapshots.forEach(snapshot -> {
                marketEventPublisher.publishKlineUpdate(toKlinePayload(snapshot));
                marketAnalyticsWriter.writeKline(snapshot);
                marketWebSocketPushService.publishKline(snapshot);
            });
        });
    }

    return standardQuote;
}
```

**关键变化**：
- 主线程仅做 Redis 写 + K 线计算（必须同步）
- 其他 4 个下游异步提交，按 symbol 分片保 FIFO
- finalized K 线作为独立异步任务（与上面的 dispatch 任务共享同一 symbol 分片，仍保 FIFO）

#### 3.3.2 新增 `MarketQuoteDownstreamDispatcher`

```java
@Component
public class MarketQuoteDownstreamDispatcher {

    private final ThreadPoolExecutor[] partitions;
    private final int partitionCount;
    private final MeterRegistry meterRegistry;

    public MarketQuoteDownstreamDispatcher(
            @Value("${falconx.market.ingestion.downstream-dispatcher.partition-count:16}") int partitionCount,
            @Value("${falconx.market.ingestion.downstream-dispatcher.queue-capacity:10000}") int queueCapacity,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.partitionCount = partitionCount;
        this.partitions = new ThreadPoolExecutor[partitionCount];
        for (int i = 0; i < partitionCount; i++) {
            this.partitions[i] = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(queueCapacity),
                    new SymbolPartitionThreadFactory(i),
                    // 拒绝策略：CallerRunsPolicy — 队列满时退化为同步执行，保证不丢消息
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );
        }
        this.meterRegistry = meterRegistry.getIfAvailable();
        if (this.meterRegistry != null) {
            for (int i = 0; i < partitionCount; i++) {
                int idx = i;
                Gauge.builder("market.ingestion.dispatcher.queue.size",
                              () -> partitions[idx].getQueue().size())
                     .tag("partition", String.valueOf(idx))
                     .register(this.meterRegistry);
            }
        }
    }

    public void dispatch(String symbol, Runnable task) {
        int idx = Math.abs(symbol.hashCode()) % partitionCount;
        partitions[idx].execute(task);
    }
}
```

**关键设计点**：
- `partition-count: 16` — 16 个独立线程，CPU 16 核以下均能利用
- `queue-capacity: 10000` — 每 partition 上限 10k 任务，按 600 q/s / 16 partition = 37 q/s/partition 计算，10k 队列约 270s 缓冲
- `CallerRunsPolicy` — 队列满时**退化为同步执行**而非丢消息，保证不丢数据但可能拖慢主线程（warning 指标可观察）
- Gauge `market.ingestion.dispatcher.queue.size` × partition_idx — Prometheus 监控背压

### 3.4 异常隔离

异步任务内部异常处理：

```java
quoteDownstreamDispatcher.dispatch(symbol, () -> {
    try {
        marketAnalyticsWriter.writeQuoteTick(quote);
    } catch (Exception ex) {
        log.warn("market.ingestion.async.clickhouse.failed symbol={} reason={}", symbol, ex.toString());
        // 不重抛 — 失败仅观测，由 ClickHouse 自身重试机制 / 监控发现
    }
    try {
        marketEventPublisher.publishPriceTick(toPriceTickPayload(quote));
    } catch (Exception ex) {
        log.warn("market.ingestion.async.kafka.failed symbol={} reason={}", symbol, ex.toString());
    }
    // 其他下游同款
});
```

**设计：每个下游独立 try-catch，任一失败不影响其他**。Kafka 失败已有 Spring Kafka producer 内置 retry；ClickHouse 失败可能由 `MybatisClickHouseMarketAnalyticsWriter` 内部处理；WebSocket 失败仅日志（前端会重新订阅）。

### 3.5 顺序保证证据

- 同 symbol 的所有 quote 进入同一 partition（hash 决定）
- 同 partition 是 single-thread executor，FIFO 队列
- 所以同 symbol 内 quote 顺序严格保持

**唯一例外**：当队列满 CallerRunsPolicy 触发同步执行 — 此时主线程跨越 partition 边界，**理论上**可能让某个 symbol 的 quote 在两个线程上间穿。

**风险评估**：CallerRunsPolicy 是降级路径，正常 600 q/s × 6 个下游 = 3600 任务/s，16 partition × 10k 队列 = 160k 缓冲，需要持续高峰 44s 才会触发。生产中应该极其罕见，运维监控 `dispatcher.queue.size > 5000` 时告警。

### 3.6 兼容性

- **Kafka topic / payload 不变**：仍发 `market.price.tick` / `market.kline.update`，只是 publish 时机延后 5-50ms
- **Redis 写不变**：保持同步
- **trading-core 消费**：消费的 Kafka 事件顺序仍保持（按 symbol 分区 → consumer 拉取顺序仍 FIFO）
- **WebSocket 前端**：推送略延迟，UI 仍按时间戳 ordering，体感无变化
- **ClickHouse 分析**：批量入队延迟略增，最终一致

### 3.7 调用方影响

| 调用 | 影响 |
|---|---|
| LP `SocketIoLpMarketQuoteProvider` 推 quote | 同步 ingest() 返回时间从 4-12ms → 1-3ms（仅 Redis + Kline）；外层 dispatchQuote 已经异步，进一步缩短关键路径 |
| trading-core Kafka consumer | 收到 price.tick 事件略延后 5-50ms，但顺序保持 |
| 客户端 WebSocket 订阅 | 价格推送略延后，但顺序保持 |
| Prometheus / Grafana | 新增 `market.ingestion.dispatcher.queue.size{partition=N}` 指标 |

## 4. 失败语义

| 失败点 | 行为 |
|---|---|
| 主线程同步 `writeLatestQuote` 失败（Redis down） | 抛异常 → ingest() 失败，上游 dispatchQuote 接住记 warn；Kline 不推进；下游全部跳过 |
| 主线程同步 `klineAggregationService.onQuote` 失败 | 同上 |
| 异步任务内 ClickHouse 写失败 | warn 日志，不重抛；Kafka / WebSocket 继续；用户感觉不到 |
| 异步任务内 Kafka send 失败 | warn 日志，不重抛；trading-core 该 tick 收不到（依赖 ingest stale tolerance） |
| 异步任务内 WebSocket push 失败 | warn 日志，前端可能丢一次 push（已有 stale scan 兜底） |
| Dispatcher queue 满（极端） | CallerRunsPolicy 主线程同步跑下游 — 拖慢 ingest 但不丢消息 |

**与现状对比**：

- 现状：任一下游失败抛异常 → 主线程感知 → 后续下游全跳过 → 数据不一致
- 改后：下游失败相互隔离 → 数据可能局部不一致但容错性强 → 监控可见

### 3.8 顺序保证边界

**强保证**：同 symbol 内 quote 顺序（含 finalized K 线在同 symbol 内顺序）

**弱保证**：跨 symbol 顺序（本来现状也只是按 ingest 顺序，不保跨 symbol 严格）

**不保证**：CallerRunsPolicy 降级时同 symbol 的"主线程同步执行"与"前序任务在 worker 线程中执行"可能并发 — 但极其罕见，且 single partition 是 single thread，CallerRunsPolicy 会让主线程**等待 partition queue 有空位**才执行，不会真正并发。

## 5. 测试矩阵

| 测试用例 | 期望 |
|---|---|
| 单 tick 异步 happy path | dispatcher.dispatch 被调 1 次（4 个下游打包提交）；主线程仅 Redis + Kline 同步完成；ingest() 返回时间 < 2ms |
| Finalized K 线 tick | dispatcher.dispatch 被调 2 次（一般 + finalized） |
| 100 tick 同 symbol 顺序保证 | 所有任务都在同一 partition；按 ingest 顺序处理；验证下游收到的顺序与 ingest 顺序一致 |
| 异步 ClickHouse 写抛异常 | warn log；Kafka publish 仍正常；ingest() 不抛 |
| 异步 Kafka send 抛异常 | warn log；WebSocket push 仍正常 |
| 队列满 CallerRunsPolicy | dispatcher.queue.size 达到 capacity；后续 dispatch 在主线程同步执行；ingest() 返回时间显著变长；Prometheus 指标可见 |
| Non-executable quote 路径 | 仅同步 publishPriceTick；dispatcher.dispatch never 被调 |
| 跨 symbol 并发 | 不同 symbol 的 tick 进不同 partition；并行处理；速度提升 |

## 6. 风险与回滚

### 6.1 风险

1. **CallerRunsPolicy 拖慢主线程**：极端高峰时退化为同步，主线程 LP 接收回调会因此延迟。监控 `dispatcher.queue.size > 5000` 告警；考虑动态扩容 partition_count（运维介入）。
2. **同 symbol 内 finalized K 线与活动 tick 顺序**：finalized 是 K 线收盘瞬间的快照，必须在该 symbol 的 quote 之后处理。当前设计已保证（两个 dispatch 都在同一 partition）。
3. **Kafka send 异常处理 vs trading-core 消费**：异步化后 Kafka send 失败仅 warn 不重抛 — trading-core 该 tick 收不到。但现状也是同步 send 失败抛异常，外层吞掉 — 行为几乎一致。
4. **partition_count 选择**：默认 16，按 CPU 核数调整；过多浪费上下文切换，过少限制并行。可通过 yml 调整。
5. **测试覆盖难度**：异步代码单测需要 `await` 任务完成，可用 `ConcurrentHashMap.await` / `CountDownLatch` / 同步 dispatcher 注入。
6. **feature flag**：建议保留 `falconx.market.ingestion.async-downstream.enabled` 默认 true，回退到同步路径。同 S6。

### 6.2 回滚

- feature flag `false` 即回退
- 单 commit revert
- 不涉及 schema / Kafka payload / 状态机变更

## 7. R6 验证清单

- [ ] 单元测试覆盖 §5 测试矩阵 8 项
- [ ] 集成测试：本地连 LP 跑 5 分钟，对比 `market.ingestion.dispatcher.queue.size` 在不同 partition 的分布 + 平均 < 100
- [ ] 顺序保证测试：单 symbol 高频 tick，验证 trading-core 收到的 Kafka 事件按 ingest 顺序
- [ ] 压测：用脚本伪造 5000 q/s tick，验证主线程不被阻塞，dispatcher queue 不溢出
- [ ] 部署 demo 24h，对比 market-service CPU 占用、Kafka publish lag、WebSocket push lag

## 8. 涉及范围

| 文件 | 改动 |
|---|---|
| `falconx-market-service/src/main/java/com/falconx/market/application/MarketQuoteDownstreamDispatcher.java`（新） | symbol 分片 dispatcher + Prometheus Gauge |
| `falconx-market-service/src/main/java/com/falconx/market/application/MarketDataIngestionApplicationService.java` | ingestPlatformQuote 主链路重构 |
| `falconx-market-service/src/main/resources/application.yml` | partition-count + queue-capacity + feature flag |
| `falconx-market-service/src/test/java/com/falconx/market/application/MarketDataIngestionAsyncTests.java`（新） | 异步路径单测 |
| `falconx-market-service/src/test/java/com/falconx/market/application/MarketQuoteDownstreamDispatcherTests.java`（新） | dispatcher 单测 |
| `scripts/s5-load-test.sh`（新） | 压测脚本 |

## 9. 不做（YAGNI）

- ❌ 不引入新中间件（不上独立 MQ）
- ❌ 不动 Redis 写策略（同步保持）
- ❌ 不修 saveLastValid 8 次 put（独立工单 — 性能报告 §3 P1）
- ❌ 不动 ClickHouse flush 策略
- ❌ 不变更 Kafka topic / partition
- ❌ 不动 K 线聚合算法（保持同步内存计算）
- ❌ 不解决跨 symbol 顺序保证（业务上不需要）
- ❌ 不动 SocketIoLpMarketQuoteProvider 回调下沉（已在 Sprint 2 S4 修）

## 10. 待用户确认事项

1. **partition_count 默认值**：16 是否合适？看 demo 服务器 CPU 核数（看运行时观测可能 8 核）
2. **CallerRunsPolicy vs AbortPolicy**：极端拥塞时退化同步（不丢消息但拖慢）vs 直接拒绝（保护主线程但丢消息）—— 本设计默认 CallerRuns
3. **feature flag**：保留 `falconx.market.ingestion.async-downstream.enabled` 默认 true？同 S6
4. **finalized K 线是否单独 dispatch**：本设计与一般 tick 共享同一 partition；如要分开成独立 partition，会破坏"同 symbol 顺序"
5. **Kafka send 失败是否进 outbox**：当前直接同步 send + 失败仅 warn；性能报告 §3 P1 提到 Kafka producer 缺 batch/linger 配置（另一个独立工单）。本设计不引入 outbox，保持简化

## 11. 后续

design approve 后 → 创建 implementation plan：

- 文件路径：`docs/superpowers/plans/2026-05-26-s5-market-ingestion-async-plan.md`
- 任务依赖：dispatcher 类 → application.yml 配置 → ingestion service 重构 → 单测 → 集成 → 部署节奏（S5 独立 market-service，可与 C1/S6 解耦部署）
