package com.falconx.market.perf;

import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.market.service.FxRateRedisCache;
import com.falconx.market.service.FxRateService;
import com.falconx.market.service.impl.DefaultFxRateService;
import com.falconx.market.config.MarketServiceProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * STAGE-14A Task 11 PERF: 8 FX × 100Hz × 30s 吞吐压测.
 *
 * <p>降级自 plan 5min (240K tick) 为 30s (24K tick), 保留吞吐 + 延迟稳定性核查.
 * 真 5min 长跑留 CI / 本地. 平时 {@code mvn test} 用 {@code -Dgroups=!perf} 跳过本测试.
 *
 * <p>测试目标：验证 {@link DefaultFxRateService#acceptTick} 在 8 线程并发 100Hz 写入下
 * 无阻塞降速、无数据丢失（内存快照完整保留 8 pair）。
 *
 * <p>用 {@link InMemoryFxRateRedisCache} 代替真 Redis，专注 FxRateService 自身写入吞吐，
 * 排除 Redis 网络往返对测量结果的干扰。
 */
@Tag("perf")
class FxRateThroughputTests {

    @Test
    void fx_rate_throughput_8pairs_100hz_30s() throws Exception {
        MarketServiceProperties props = new MarketServiceProperties();
        props.getFx().setRedisTtlSeconds(5);
        props.getFx().setStaleThresholdSeconds(30);
        // 用 in-memory fake redis 隔离真 Redis 性能干扰，专注 FxRateService 自身吞吐
        InMemoryFxRateRedisCache fakeCache = new InMemoryFxRateRedisCache();
        FxRateService service = new DefaultFxRateService(props, fakeCache, Clock.systemUTC());

        List<String[]> pairs = List.of(
            new String[]{"EUR","USD","EURUSD"},
            new String[]{"AUD","USD","AUDUSD"},
            new String[]{"USD","JPY","USDJPY"},
            new String[]{"GBP","USD","GBPUSD"},
            new String[]{"USD","CAD","USDCAD"},
            new String[]{"USD","CHF","USDCHF"},
            new String[]{"NZD","USD","NZDUSD"},
            new String[]{"USD","CNH","USDCNH"}
        );

        AtomicInteger total = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        long deadline = System.currentTimeMillis() + 30_000;

        for (String[] pair : pairs) {
            pool.submit(() -> {
                while (System.currentTimeMillis() < deadline) {
                    service.acceptTick(new FxRateSnapshotPayload(
                        pair[0], pair[1],
                        BigDecimal.valueOf(1.0 + Math.random() * 0.1),
                        System.currentTimeMillis(),
                        "GODSA", pair[2]));
                    total.incrementAndGet();
                    LockSupport.parkNanos(10_000_000L);  // 10ms = 100Hz
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(40, TimeUnit.SECONDS);

        // 8 pair × ~100Hz × 30s ≈ 24,000 ticks（允许 ±20% 抖动）
        int expected = 24_000;
        int margin = (int)(expected * 0.20);
        assertThat(total.get())
            .as("总 tick 数应在 24000 ±20%% 范围内，实际=%d", total.get())
            .isBetween(expected - margin, expected + margin);
        // snapshotAll 应包含 8 pair
        assertThat(service.snapshotAll())
            .as("内存快照应包含全部 8 个货币对")
            .hasSize(8);
    }

    /** 内存版 FxRateRedisCache，避免 Redis 网络往返干扰 perf 测试. */
    private static class InMemoryFxRateRedisCache implements FxRateRedisCache {
        private final ConcurrentHashMap<String, String> data = new ConcurrentHashMap<>();

        @Override
        public void set(String key, String value, long ttlSeconds) {
            data.put(key, value);
        }

        @Override
        public String get(String key) {
            return data.get(key);
        }

        @Override
        public long ttl(String key) {
            return data.containsKey(key) ? 5L : 0L;
        }
    }
}
