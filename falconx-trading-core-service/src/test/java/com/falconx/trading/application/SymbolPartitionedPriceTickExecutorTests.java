package com.falconx.trading.application;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 高频报价 tick 执行器分区语义测试。
 */
class SymbolPartitionedPriceTickExecutorTests {

    @Test
    void shouldRouteSameSymbolToSameWorkerInSubmissionOrder() throws Exception {
        SymbolPartitionedPriceTickExecutor executor = new SymbolPartitionedPriceTickExecutor(4);
        List<String> events = new ArrayList<>();
        List<String> threads = new ArrayList<>();

        try {
            Future<?> first = executor.submit("BTCUSDT", () -> {
                events.add("first");
                threads.add(Thread.currentThread().getName());
            });
            Future<?> second = executor.submit("btcusdt", () -> {
                events.add("second");
                threads.add(Thread.currentThread().getName());
            });
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
            Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        Assertions.assertEquals(List.of("first", "second"), events);
        Assertions.assertEquals(2, threads.size());
        Assertions.assertEquals(threads.get(0), threads.get(1),
                "同一 symbol 必须落到同一个单线程 worker，保证本地处理顺序");
    }

    @Test
    void shouldExposeQueuedTaskCountPerWorker() throws Exception {
        SymbolPartitionedPriceTickExecutor executor = new SymbolPartitionedPriceTickExecutor(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        int workerIndex = executor.workerIndexFor("BTCUSDT");

        try {
            Future<?> first = executor.submit("BTCUSDT", () -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            });
            Assertions.assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            Future<?> second = executor.submit("btcusdt", () -> {
            });

            Assertions.assertEquals(1, executor.queuedTaskCount(workerIndex));
            Assertions.assertEquals(1, executor.totalQueuedTaskCount());

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
            Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
