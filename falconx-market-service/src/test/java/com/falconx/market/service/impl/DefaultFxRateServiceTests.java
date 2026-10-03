package com.falconx.market.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.contract.FxRateSnapshotPayload;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DefaultFxRateService} 单元测试。
 *
 * <p>通过 {@link FakeRedisCommands}（内存 Redis）和 {@link FakeClock}（可控时钟）
 * 替换外部依赖，所有测试无 I/O、无 Spring 上下文。
 */
class DefaultFxRateServiceTests {

    private DefaultFxRateService service;
    private MarketServiceProperties props;
    private FakeRedisCommands fakeRedis;
    private FakeClock clock;

    @BeforeEach
    void setUp() {
        props = new MarketServiceProperties();
        props.getFx().setRedisTtlSeconds(5);
        props.getFx().setStaleThresholdSeconds(30);
        fakeRedis = new FakeRedisCommands();
        clock = new FakeClock();
        service = new DefaultFxRateService(props, fakeRedis, clock);
    }

    /**
     * acceptTick 必须把 rate 写入 Redis，key 格式为 falconx:fx:rate:{base}:{quote}，
     * 且 TTL 与 props.getFx().getRedisTtlSeconds() 一致。
     */
    @Test
    void accept_writes_redis_with_ttl() {
        service.acceptTick(new FxRateSnapshotPayload(
                "EUR", "USD", new BigDecimal("1.0800"),
                clock.nowMillis(), "GODSA", "EURUSD"));

        assertThat(fakeRedis.get("falconx:fx:rate:EUR:USD")).isEqualTo("1.0800");
        assertThat(fakeRedis.ttl("falconx:fx:rate:EUR:USD")).isEqualTo(5);
    }

    /**
     * 写入直接对后，queryRate 应返回精度为 8 位的汇率值。
     */
    @Test
    void query_returns_direct_rate() {
        service.acceptTick(new FxRateSnapshotPayload(
                "EUR", "USD", new BigDecimal("1.0800"),
                clock.nowMillis(), "GODSA", "EURUSD"));

        Optional<BigDecimal> result = service.queryRate("EUR", "USD");
        assertThat(result).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1.08000000"));
    }

    /**
     * 写入 EUR/USD 和 AUD/USD 后，queryRate("EUR","AUD") 应通过 USD pivot 交叉换算：
     * EUR→AUD = (EUR→USD) / (AUD→USD) = 1.08 / 0.65 ≈ 1.66153846。
     */
    @Test
    void query_returns_cross_via_usd() {
        service.acceptTick(new FxRateSnapshotPayload(
                "EUR", "USD", new BigDecimal("1.0800"),
                clock.nowMillis(), "GODSA", "EURUSD"));
        service.acceptTick(new FxRateSnapshotPayload(
                "AUD", "USD", new BigDecimal("0.6500"),
                clock.nowMillis(), "GODSA", "AUDUSD"));

        Optional<BigDecimal> result = service.queryRate("EUR", "AUD");
        assertThat(result).hasValueSatisfying(v ->
                assertThat(v).isEqualByComparingTo("1.66153846"));
    }

    /**
     * queryRate("USD","USD") 应直接返回 1，不依赖任何已写入的 tick。
     */
    @Test
    void same_currency_returns_one() {
        Optional<BigDecimal> result = service.queryRate("USD", "USD");
        assertThat(result).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1"));
    }

    /**
     * 写入 tick 后，时间推进超过 staleThresholdSeconds（30s），isStale 应返回 true。
     */
    @Test
    void is_stale_returns_true_after_threshold() {
        service.acceptTick(new FxRateSnapshotPayload(
                "EUR", "USD", new BigDecimal("1.0800"),
                clock.nowMillis(), "GODSA", "EURUSD"));

        clock.advance(31_000);  // > 30s
        assertThat(service.isStale("EUR", "USD")).isTrue();
    }

    /**
     * snapshotAll 应返回所有已接收货币对的快照，数量与写入次数一致。
     */
    @Test
    void snapshot_returns_all_known_pairs() {
        service.acceptTick(new FxRateSnapshotPayload(
                "EUR", "USD", new BigDecimal("1.0800"),
                clock.nowMillis(), "GODSA", "EURUSD"));
        service.acceptTick(new FxRateSnapshotPayload(
                "USD", "JPY", new BigDecimal("150"),
                clock.nowMillis(), "GODSA", "USDJPY"));

        assertThat(service.snapshotAll()).hasSize(2);
    }

    /**
     * 写入 tick 后，时间推进未超过 staleThresholdSeconds（29s &lt; 30s），isStale 应返回 false。
     *
     * <p>边界验证：{@code ageSec == threshold} 时 {@code >} 使结果为 false，符合"超过才告警"语义。
     */
    @Test
    void is_stale_returns_false_within_threshold() {
        service.acceptTick(new FxRateSnapshotPayload(
                "EUR", "USD", new BigDecimal("1.0800"),
                clock.nowMillis(), "GODSA", "EURUSD"));

        clock.advance(29_000);  // < 30s
        assertThat(service.isStale("EUR", "USD")).isFalse();
    }
}
