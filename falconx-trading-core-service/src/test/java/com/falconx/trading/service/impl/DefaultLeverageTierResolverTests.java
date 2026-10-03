package com.falconx.trading.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.trading.entity.SymbolLeverageTier;
import com.falconx.trading.repository.SymbolLeverageTierRepository;
import com.falconx.trading.service.model.LeverageTier;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link DefaultLeverageTierResolver} 单元测试（mock {@link SymbolLeverageTierRepository}）。
 *
 * <p>覆盖：notional 落 tier1（lower=0）、落中间档边界（lower 含 / upper 不含）、
 * 落最高档（upper=null 无上限）、缓存命中不重复查、缓存过期重查、空配置 empty、
 * group 透传给 Repository。
 */
@ExtendWith(MockitoExtension.class)
class DefaultLeverageTierResolverTests {

    @Mock
    private SymbolLeverageTierRepository repository;

    private static SymbolLeverageTier tier(int tierNo, BigDecimal lower, BigDecimal upper,
                                           int maxLev, String mmRate) {
        return new SymbolLeverageTier(
                (long) tierNo, "EURUSD", "vip", tierNo, lower, upper, maxLev,
                new BigDecimal(mmRate), Boolean.TRUE);
    }

    /** 标准 3 档：[0,100000) / [100000,500000) / [500000,∞)。 */
    private static List<SymbolLeverageTier> threeTiers() {
        return List.of(
                tier(1, BigDecimal.ZERO, new BigDecimal("100000"), 500, "0.005000"),
                tier(2, new BigDecimal("100000"), new BigDecimal("500000"), 200, "0.010000"),
                tier(3, new BigDecimal("500000"), null, 100, "0.020000"));
    }

    private DefaultLeverageTierResolver newResolver(Duration ttl, AtomicReference<Instant> clock) {
        return new DefaultLeverageTierResolver(repository, ttl, clock::get);
    }

    @Test
    void resolve_notional落tier1下界含() {
        when(repository.findTiers("EURUSD", "vip")).thenReturn(threeTiers());
        var clock = new AtomicReference<>(Instant.EPOCH);
        var resolver = newResolver(Duration.ofSeconds(30), clock);

        Optional<LeverageTier> result = resolver.resolve("EURUSD", BigDecimal.ZERO, "vip");

        assertThat(result).isPresent();
        assertThat(result.get().tierNo()).isEqualTo(1);
        assertThat(result.get().maxLeverage()).isEqualTo(500);
        assertThat(result.get().mmRate()).isEqualByComparingTo("0.005000");
    }

    @Test
    void resolve_等于中间档lower落本档_等于upper落下一档() {
        when(repository.findTiers("EURUSD", "vip")).thenReturn(threeTiers());
        var clock = new AtomicReference<>(Instant.EPOCH);
        var resolver = newResolver(Duration.ofSeconds(30), clock);

        // 等于 tier2 lower（100000）→ 落 tier2（lower 含）
        assertThat(resolver.resolve("EURUSD", new BigDecimal("100000"), "vip"))
                .get().extracting(LeverageTier::tierNo).isEqualTo(2);
        // 等于 tier2 upper（500000）→ 落 tier3（upper 不含）
        assertThat(resolver.resolve("EURUSD", new BigDecimal("500000"), "vip"))
                .get().extracting(LeverageTier::tierNo).isEqualTo(3);
        // 中间值（300000）→ 落 tier2
        assertThat(resolver.resolve("EURUSD", new BigDecimal("300000"), "vip"))
                .get().extracting(LeverageTier::tierNo).isEqualTo(2);
    }

    @Test
    void resolve_落最高档upper为null无上限() {
        when(repository.findTiers("EURUSD", "vip")).thenReturn(threeTiers());
        var clock = new AtomicReference<>(Instant.EPOCH);
        var resolver = newResolver(Duration.ofSeconds(30), clock);

        Optional<LeverageTier> result = resolver.resolve("EURUSD", new BigDecimal("9999999999"), "vip");

        assertThat(result).isPresent();
        assertThat(result.get().tierNo()).isEqualTo(3);
        assertThat(result.get().notionalUpper()).isNull();
    }

    @Test
    void resolve_缓存命中同symbol_group二次resolve不再查Repository() {
        when(repository.findTiers("EURUSD", "vip")).thenReturn(threeTiers());
        var clock = new AtomicReference<>(Instant.EPOCH);
        var resolver = newResolver(Duration.ofSeconds(30), clock);

        resolver.resolve("EURUSD", new BigDecimal("50000"), "vip");
        // 时间未越过 TTL，二次（不同 notional 同 key）走缓存
        clock.set(Instant.EPOCH.plusSeconds(29));
        resolver.resolve("EURUSD", new BigDecimal("200000"), "vip");

        verify(repository, times(1)).findTiers("EURUSD", "vip");
    }

    @Test
    void resolve_缓存过期后重查Repository() {
        when(repository.findTiers("EURUSD", "vip")).thenReturn(threeTiers());
        var clock = new AtomicReference<>(Instant.EPOCH);
        var resolver = newResolver(Duration.ofSeconds(30), clock);

        resolver.resolve("EURUSD", new BigDecimal("50000"), "vip");
        // 越过 30s TTL → 惰性过期重查
        clock.set(Instant.EPOCH.plusSeconds(31));
        resolver.resolve("EURUSD", new BigDecimal("50000"), "vip");

        verify(repository, times(2)).findTiers("EURUSD", "vip");
    }

    @Test
    void resolve_空配置返回empty() {
        when(repository.findTiers("EURUSD", "vip")).thenReturn(List.of());
        var clock = new AtomicReference<>(Instant.EPOCH);
        var resolver = newResolver(Duration.ofSeconds(30), clock);

        assertThat(resolver.resolve("EURUSD", new BigDecimal("50000"), "vip")).isEmpty();
    }

    @Test
    void resolve_groupCode原样透传给Repository() {
        when(repository.findTiers("EURUSD", "gold")).thenReturn(threeTiers());
        var clock = new AtomicReference<>(Instant.EPOCH);
        var resolver = newResolver(Duration.ofSeconds(30), clock);

        resolver.resolve("EURUSD", new BigDecimal("50000"), "gold");

        // Resolver 不重复处理 default 回退（由 Repository 内部负责），group 原样透传
        verify(repository, times(1)).findTiers("EURUSD", "gold");
    }
}
