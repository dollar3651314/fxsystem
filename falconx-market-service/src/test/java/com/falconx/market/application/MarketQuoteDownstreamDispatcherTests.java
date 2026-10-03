package com.falconx.market.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Sprint 3 S5 Task 1：MarketQuoteDownstreamDispatcher 单元测试。
 */
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
        assertTrue(threads.size() >= 2,
                "different symbols should span multiple partitions, got: " + threads);
    }

    @Test
    void dispatch_queueFull_callerRunsPolicyExecutesOnCallerThread() throws InterruptedException {
        dispatcher = new MarketQuoteDownstreamDispatcher(1, 1, asProvider(new SimpleMeterRegistry()));
        CountDownLatch blockingLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        ConcurrentHashMap<String, String> callerRunsThread = new ConcurrentHashMap<>();

        dispatcher.dispatch("SYM", () -> {
            blockingLatch.countDown();
            try {
                releaseLatch.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(blockingLatch.await(1, TimeUnit.SECONDS));

        dispatcher.dispatch("SYM", () -> {});

        String mainThread = Thread.currentThread().getName();
        dispatcher.dispatch("SYM", () -> {
            callerRunsThread.put("runs-on", Thread.currentThread().getName());
        });

        assertEquals(mainThread, callerRunsThread.get("runs-on"),
                "CallerRunsPolicy should execute on caller thread when queue is full");

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
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }
}
