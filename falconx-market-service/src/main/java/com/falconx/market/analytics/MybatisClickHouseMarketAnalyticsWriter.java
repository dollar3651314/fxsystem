package com.falconx.market.analytics;

import com.falconx.market.analytics.mapper.MarketKlineMapper;
import com.falconx.market.analytics.mapper.MarketQuoteTickMapper;
import com.falconx.market.analytics.mapper.record.MarketKlineRecord;
import com.falconx.market.analytics.mapper.record.MarketQuoteTickRecord;
import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.KlineSnapshot;
import com.falconx.market.entity.StandardQuote;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ClickHouse 市场分析写入 MyBatis 实现。
 *
 * <p>该实现把高频报价历史和已收盘 K 线统一改为 `Mapper + XML` 写入，
 * 避免在 Java 代码中出现字符串 SQL。
 */
@Component
@Profile("!stub")
public class MybatisClickHouseMarketAnalyticsWriter implements MarketAnalyticsWriter {

    private static final Logger log = LoggerFactory.getLogger(MybatisClickHouseMarketAnalyticsWriter.class);
    static final int MAX_QUOTE_BATCH_SIZE = 10_000;
    /** drop 日志按 N 次累计输出一条，避免 OOM 风险时反而把 log 刷爆。 */
    private static final int DROP_LOG_EVERY = 1_000;

    private final MarketQuoteTickMapper marketQuoteTickMapper;
    private final MarketKlineMapper marketKlineMapper;
    private final MarketServiceProperties properties;
    private final int quoteBatchSize;
    /** 队列硬上限：超过即按 drop-oldest 丢弃，避免 ClickHouse 卡顿时 OOM。 */
    private final int quoteQueueMaxSize;
    private final ConcurrentLinkedQueue<MarketQuoteTickRecord> pendingQuoteTicks = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingQuoteTickCount = new AtomicInteger();
    private final AtomicLong droppedQuoteTickCount = new AtomicLong();
    private final ReentrantLock quoteFlushLock = new ReentrantLock();
    /**
     * K 线批量入库缓冲（#2，2026-06-08）。镜像上面 quote tick 的攒批基建：writeKline 入队、
     * 定时任务批量 flush。仅影响 ClickHouse 入库频率，不影响 WebSocket 实时推送（走独立路径）。
     */
    private final int klineBatchSize;
    private final int klineQueueMaxSize;
    private final ConcurrentLinkedQueue<MarketKlineRecord> pendingKlines = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingKlineCount = new AtomicInteger();
    private final AtomicLong droppedKlineCount = new AtomicLong();
    private final ReentrantLock klineFlushLock = new ReentrantLock();
    /**
     * Micrometer registry：用 field 注入而非 constructor，避免单元测试调旧构造器需要全部修改。
     * required=false 让 stub/test profile 下没有 registry 也能跑。
     */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    public MybatisClickHouseMarketAnalyticsWriter(MarketQuoteTickMapper marketQuoteTickMapper,
                                                  MarketKlineMapper marketKlineMapper,
                                                  MarketServiceProperties properties) {
        this.marketQuoteTickMapper = marketQuoteTickMapper;
        this.marketKlineMapper = marketKlineMapper;
        this.properties = properties;
        this.quoteBatchSize = resolveQuoteBatchSize(properties.getAnalytics().getQuoteBatchSize());
        this.quoteQueueMaxSize = Math.max(properties.getAnalytics().getQuoteQueueMaxSize(), this.quoteBatchSize * 10);
        if (this.quoteBatchSize != properties.getAnalytics().getQuoteBatchSize()) {
            log.warn("market.analytics.quote.batch_size.adjusted configured={} effective={}",
                    properties.getAnalytics().getQuoteBatchSize(),
                    this.quoteBatchSize);
        }
        this.klineBatchSize = resolveQuoteBatchSize(properties.getAnalytics().getKlineBatchSize());
        this.klineQueueMaxSize = Math.max(properties.getAnalytics().getKlineQueueMaxSize(), this.klineBatchSize * 10);
        log.info("market.analytics.writer.initialized quoteBatchSize={} quoteQueueMaxSize={} quoteFlushInterval={} "
                        + "klineBatchSize={} klineQueueMaxSize={} klineFlushInterval={}",
                this.quoteBatchSize,
                this.quoteQueueMaxSize,
                properties.getAnalytics().getQuoteFlushInterval(),
                this.klineBatchSize,
                this.klineQueueMaxSize,
                properties.getAnalytics().getKlineFlushInterval());
    }

    /**
     * 注册 3 个 OOM 防御 metric 到 Micrometer，Prometheus 可爬：
     * <ul>
     *   <li>{@code falconx.market.quote.pending.size} — 当前队列深度，水位告警源</li>
     *   <li>{@code falconx.market.quote.dropped.total} — 累计丢弃 tick 数，非零说明触发过容量保护</li>
     *   <li>{@code falconx.market.quote.queue.max} — 配置上限（监控告警阈值基准）</li>
     * </ul>
     * 建议 Prometheus 告警：pending.size / queue.max > 0.8 持续 30s 触发；dropped.total
     * 在 5 分钟窗口内 increase() > 0 也触发（说明确实在丢数据）。
     */
    @PostConstruct
    public void registerMetrics() {
        if (meterRegistry == null) {
            log.warn("market.analytics.metrics.registry-missing reason=meter-registry-not-injected");
            return;
        }
        Gauge.builder("falconx.market.quote.pending.size", pendingQuoteTickCount, AtomicInteger::doubleValue)
                .description("当前待 flush 到 ClickHouse 的 quote tick 数量；接近 quote.queue.max 时触发 drop-oldest")
                .baseUnit("ticks")
                .register(meterRegistry);
        Gauge.builder("falconx.market.quote.dropped.total", droppedQuoteTickCount, AtomicLong::doubleValue)
                .description("累计被容量保护丢弃的 quote tick 数；非零说明 ClickHouse 写入慢于 LP push 速率")
                .baseUnit("ticks")
                .register(meterRegistry);
        Gauge.builder("falconx.market.quote.queue.max", this, w -> w.quoteQueueMaxSize)
                .description("quote tick 队列硬上限（来自 falconx.market.analytics.quote-queue-max-size）")
                .baseUnit("ticks")
                .register(meterRegistry);
        Gauge.builder("falconx.market.kline.pending.size", pendingKlineCount, AtomicInteger::doubleValue)
                .description("当前待 flush 到 ClickHouse 的已收盘 K 线数量；接近 kline.queue.max 时触发 drop-oldest")
                .baseUnit("klines")
                .register(meterRegistry);
        Gauge.builder("falconx.market.kline.dropped.total", droppedKlineCount, AtomicLong::doubleValue)
                .description("累计被容量保护丢弃的已收盘 K 线数；非零说明 ClickHouse 写入持续慢于收盘速率（=永久历史缺口）")
                .baseUnit("klines")
                .register(meterRegistry);
        log.info("market.analytics.metrics.registered count=5");
    }

    @Override
    public void writeQuoteTick(StandardQuote quote) {
        // 高频 Tick 不再逐条同步写 ClickHouse。
        // 当前实现把标准化后的记录先放入进程内缓冲区，再由定时任务统一批量落库，
        // 以稳定控制 ClickHouse 写入频率，减少单条 INSERT 带来的网络往返和写入放大。
        //
        // 容量保护（2026-05-20 引入）：ClickHouse 短暂不可用时 LP 仍在 push，原实现无上限直至 OOM。
        // 现在硬限 queueMaxSize：超额时丢弃队头（最老）的 tick，保新数据。下游 ClickHouse 是
        // "市场分析历史"，丢失少量最旧 tick 比让整个 market-service 死锁可接受得多。
        if (pendingQuoteTickCount.get() >= quoteQueueMaxSize) {
            MarketQuoteTickRecord dropped = pendingQuoteTicks.poll();
            if (dropped != null) {
                pendingQuoteTickCount.decrementAndGet();
                long total = droppedQuoteTickCount.incrementAndGet();
                if (total % DROP_LOG_EVERY == 1) {
                    log.warn("market.analytics.quote.queue.dropped totalDropped={} queueSize={} queueMaxSize={} reason=queue_full",
                            total,
                            pendingQuoteTickCount.get(),
                            quoteQueueMaxSize);
                }
            }
        }
        MarketQuoteTickRecord record = MarketAnalyticsMybatisSupport.toQuoteTickRecord(quote);
        pendingQuoteTicks.add(record);
        pendingQuoteTickCount.incrementAndGet();
    }

    @Override
    public void writeKline(KlineSnapshot snapshot) {
        if (!snapshot.isFinal()) {
            log.info("market.analytics.kline.skip_non_final symbol={} interval={} closeTime={}",
                    snapshot.symbol(),
                    snapshot.interval(),
                    snapshot.closeTime());
            return;
        }

        // #2（2026-06-08）：不再逐行 insert（整分钟边界 ~1571 根同时收盘 → part 爆炸 + merge 狂潮）。
        // 改为入队，由 flushKlinesOnSchedule 批量落库。仅影响入库频率，不影响 WebSocket 实时推送。
        // 容量保护：丢一根 K 线 = ReplacingMergeTree 不会重建的永久缺口，故 cap 设很高、正常不触顶；
        // 一旦触顶（ClickHouse 长时间不可用）按 drop-oldest 保新数据，并 WARN 告警。
        if (pendingKlineCount.get() >= klineQueueMaxSize) {
            MarketKlineRecord dropped = pendingKlines.poll();
            if (dropped != null) {
                pendingKlineCount.decrementAndGet();
                long total = droppedKlineCount.incrementAndGet();
                if (total % DROP_LOG_EVERY == 1) {
                    log.warn("market.analytics.kline.queue.dropped totalDropped={} queueSize={} queueMaxSize={} reason=queue_full",
                            total,
                            pendingKlineCount.get(),
                            klineQueueMaxSize);
                }
            }
        }
        pendingKlines.add(MarketAnalyticsMybatisSupport.toKlineRecord(snapshot));
        pendingKlineCount.incrementAndGet();
    }

    /**
     * 周期性刷新缓冲中的报价 Tick。
     *
     * <p>该定时任务负责统一执行 flush 逻辑：
     *
     * <ul>
     *   <li>`quote-flush-interval` 控制 ClickHouse 写入频率，默认 10 秒</li>
     *   <li>`quote-batch-size` 控制每批 INSERT 的最大记录数，默认 200 条</li>
     * </ul>
     *
     * <p>这里不使用外部回调线程直接刷盘，而是依赖 Spring 管理的调度线程执行，
     * 符合仓库里“外部回调线程不得直接执行完整下游链路”的规则。
     */
    @Scheduled(
            fixedDelayString = "${falconx.market.analytics.quote-flush-interval:10000}",
            timeUnit = TimeUnit.MILLISECONDS
    )
    public void flushQuoteTicksOnSchedule() {
        flushQuoteTicks("scheduled");
    }

    /**
     * 周期性刷新缓冲中的已收盘 K 线（#2，2026-06-08）。默认 1s 一次，覆盖整分钟边界的收盘 burst。
     * 仅控制 ClickHouse 入库频率，不影响 WebSocket 实时推送。
     */
    @Scheduled(
            fixedDelayString = "${falconx.market.analytics.kline-flush-interval:1000}",
            timeUnit = TimeUnit.MILLISECONDS
    )
    public void flushKlinesOnSchedule() {
        flushKlines("scheduled");
    }

    /**
     * 服务关闭前尝试把残留缓冲（报价 Tick + K 线）刷入 ClickHouse，尽量减少正常停机时的数据遗失。
     */
    @PreDestroy
    public void flushRemainingOnShutdown() {
        flushQuoteTicks("shutdown");
        flushKlines("shutdown");
    }

    private void flushQuoteTicks(String trigger) {
        if (!quoteFlushLock.tryLock()) {
            return;
        }
        try {
            int initialQueueSize = pendingQuoteTickCount.get();
            // 队列水位告警：超过 max 的 50% 时按 INFO 打 backlog，>=80% 时按 WARN，让运维提前发现
            if (initialQueueSize >= quoteQueueMaxSize * 0.8) {
                log.warn("market.analytics.quote.queue.backlog level=high queueSize={} queueMaxSize={} trigger={}",
                        initialQueueSize, quoteQueueMaxSize, trigger);
            } else if (initialQueueSize >= quoteQueueMaxSize * 0.5) {
                log.info("market.analytics.quote.queue.backlog level=medium queueSize={} queueMaxSize={} trigger={}",
                        initialQueueSize, quoteQueueMaxSize, trigger);
            }
            while (pendingQuoteTickCount.get() > 0) {
                List<MarketQuoteTickRecord> batch = drainQuoteTicks(quoteBatchSize);
                if (batch.isEmpty()) {
                    return;
                }
                try {
                    marketQuoteTickMapper.insertQuoteTicks(batch);
                    log.info("market.analytics.quote.flush.completed trigger={} batchSize={} queueRemaining={}",
                            trigger, batch.size(), pendingQuoteTickCount.get());
                } catch (RuntimeException exception) {
                    // ClickHouse 短暂不可用时不能直接吞掉已经出队的 Tick。
                    // 当前策略是把本批记录重新放回缓冲尾部，并恢复计数，再等待下一次阈值或定时刷新重试。
                    // 这样虽然可能打乱极小范围内的入队顺序，但能保证"批量失败不丢数"。
                    // 注意：writeQuoteTick 的 capacity guard 仍然在持续生效，重新入队后下个新 tick
                    // 仍会按 drop-oldest 策略保持队列上限。
                    restoreQuoteTicks(batch);
                    log.error("market.analytics.quote.flush.failed trigger={} batchSize={} queueSize={}",
                            trigger,
                            batch.size(),
                            pendingQuoteTickCount.get(),
                            exception);
                    return;
                }
            }
        } finally {
            quoteFlushLock.unlock();
        }
    }

    /** 外部可观测的 metric（监控/健康检查可读，方便接入 actuator 指标）。 */
    public int getPendingQuoteTickCount() {
        return pendingQuoteTickCount.get();
    }

    public long getDroppedQuoteTickCount() {
        return droppedQuoteTickCount.get();
    }

    public int getQuoteQueueMaxSize() {
        return quoteQueueMaxSize;
    }

    private List<MarketQuoteTickRecord> drainQuoteTicks(int batchSize) {
        List<MarketQuoteTickRecord> batch = new ArrayList<>(batchSize);
        while (batch.size() < batchSize) {
            MarketQuoteTickRecord record = pendingQuoteTicks.poll();
            if (record == null) {
                break;
            }
            batch.add(record);
            pendingQuoteTickCount.decrementAndGet();
        }
        return batch;
    }

    static int resolveQuoteBatchSize(int configuredQuoteBatchSize) {
        if (configuredQuoteBatchSize < 1) {
            return 1;
        }
        return Math.min(configuredQuoteBatchSize, MAX_QUOTE_BATCH_SIZE);
    }

    private void restoreQuoteTicks(List<MarketQuoteTickRecord> batch) {
        batch.forEach(pendingQuoteTicks::add);
        pendingQuoteTickCount.addAndGet(batch.size());
    }

    /**
     * 刷新缓冲中的已收盘 K 线（#2）。镜像 {@link #flushQuoteTicks(String)}：独立锁 + 批量 insert +
     * 失败 restore 回队列重试，保证"批量失败不丢数"。
     */
    private void flushKlines(String trigger) {
        if (!klineFlushLock.tryLock()) {
            return;
        }
        try {
            while (pendingKlineCount.get() > 0) {
                List<MarketKlineRecord> batch = drainKlines(klineBatchSize);
                if (batch.isEmpty()) {
                    return;
                }
                try {
                    marketKlineMapper.insertKlines(batch);
                    log.info("market.analytics.kline.flush.completed trigger={} batchSize={} queueRemaining={}",
                            trigger, batch.size(), pendingKlineCount.get());
                } catch (RuntimeException exception) {
                    restoreKlines(batch);
                    log.error("market.analytics.kline.flush.failed trigger={} batchSize={} queueSize={}",
                            trigger,
                            batch.size(),
                            pendingKlineCount.get(),
                            exception);
                    return;
                }
            }
        } finally {
            klineFlushLock.unlock();
        }
    }

    private List<MarketKlineRecord> drainKlines(int batchSize) {
        List<MarketKlineRecord> batch = new ArrayList<>(batchSize);
        while (batch.size() < batchSize) {
            MarketKlineRecord record = pendingKlines.poll();
            if (record == null) {
                break;
            }
            batch.add(record);
            pendingKlineCount.decrementAndGet();
        }
        return batch;
    }

    private void restoreKlines(List<MarketKlineRecord> batch) {
        batch.forEach(pendingKlines::add);
        pendingKlineCount.addAndGet(batch.size());
    }

    /** 外部可观测的 metric（监控/健康检查可读）。 */
    public int getPendingKlineCount() {
        return pendingKlineCount.get();
    }

    public long getDroppedKlineCount() {
        return droppedKlineCount.get();
    }
}
