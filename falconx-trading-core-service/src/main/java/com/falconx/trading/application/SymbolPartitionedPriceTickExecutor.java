package com.falconx.trading.application;

import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 按 symbol 分区的价格 tick 执行器。
 *
 * <p>同一个 symbol 固定进入同一个单线程 worker，保证本地处理顺序；不同 symbol
 * 可分散到不同 worker，避免所有报价共享一个全局单线程队列。
 */
public class SymbolPartitionedPriceTickExecutor {

    private final ThreadPoolExecutor[] workers;

    public SymbolPartitionedPriceTickExecutor(int workerCount) {
        int safeWorkerCount = Math.max(1, workerCount);
        this.workers = new ThreadPoolExecutor[safeWorkerCount];
        for (int i = 0; i < safeWorkerCount; i++) {
            int workerIndex = i;
            workers[i] = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    Thread.ofPlatform().name("trading-price-tick-" + workerIndex + "-", 0).factory()
            );
        }
    }

    public Future<?> submit(String symbol, Runnable task) {
        return workerFor(symbol).submit(task);
    }

    public void shutdown() {
        for (ThreadPoolExecutor worker : workers) {
            worker.shutdown();
        }
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        for (ThreadPoolExecutor worker : workers) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0 || !worker.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) {
                return false;
            }
        }
        return true;
    }

    public int workerCount() {
        return workers.length;
    }

    public int queuedTaskCount(int workerIndex) {
        if (workerIndex < 0 || workerIndex >= workers.length) {
            return 0;
        }
        return workers[workerIndex].getQueue().size();
    }

    public int totalQueuedTaskCount() {
        int total = 0;
        for (int i = 0; i < workers.length; i++) {
            total += queuedTaskCount(i);
        }
        return total;
    }

    public int workerIndexFor(String symbol) {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        return Math.floorMod(normalized.hashCode(), workers.length);
    }

    private ThreadPoolExecutor workerFor(String symbol) {
        return workers[workerIndexFor(symbol)];
    }
}
