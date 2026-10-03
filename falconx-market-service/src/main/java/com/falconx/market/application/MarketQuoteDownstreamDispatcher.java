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
            this.partitions[i] = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(queueCapacity),
                    new MarketQuoteDownstreamThreadFactory(i),
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
