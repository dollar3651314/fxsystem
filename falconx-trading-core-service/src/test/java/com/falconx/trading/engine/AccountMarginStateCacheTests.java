package com.falconx.trading.engine;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.repository.TradingAccountRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@link AccountMarginStateCache} 单元测试（STAGE-14C1 Task 9）。
 *
 * <p>锁定账户内存缓存三要素（AGENTS §3.9）：
 * <ul>
 *   <li>TTL：窗口内命中缓存不打 DB；窗口外过期重新读 DB。</li>
 *   <li>刷新：{@code invalidate(userId)}（开 / 平 / 落账后）使该用户下一次读重新打 DB。</li>
 *   <li>降级：cache miss / 过期 / 失效一律读 DB（owner Repository）。</li>
 * </ul>
 */
class AccountMarginStateCacheTests {

    private static final String CCY = "USDT";

    private TradingAccount account(long userId, String balance) {
        return new TradingAccount(
                1000L + userId,
                userId,
                CCY,
                new BigDecimal(balance),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                TradingMarginMode.ISOLATED,
                null,
                null,
                OffsetDateTime.parse("2026-05-29T00:00:00Z"),
                OffsetDateTime.parse("2026-05-29T00:00:00Z"));
    }

    @Test
    void shouldReadDbOnMissThenServeFromCacheWithinTtl() {
        TradingAccountRepository repository = mock(TradingAccountRepository.class);
        when(repository.findByUserIdAndCurrency(1L, CCY)).thenReturn(Optional.of(account(1L, "100")));
        AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-05-29T00:00:00Z"));
        AccountMarginStateCache cache = new AccountMarginStateCache(repository, clock::get);

        // 第一次 miss → 打 DB
        TradingAccount first = cache.getAccount(1L, CCY);
        Assertions.assertEquals(new BigDecimal("100"), first.balance());

        // TTL 窗口内（+500ms）再读 → 命中缓存，不再打 DB
        clock.set(Instant.parse("2026-05-29T00:00:00.500Z"));
        TradingAccount second = cache.getAccount(1L, CCY);
        Assertions.assertEquals(new BigDecimal("100"), second.balance());

        verify(repository, times(1)).findByUserIdAndCurrency(1L, CCY);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void shouldReloadFromDbAfterTtlExpires() {
        TradingAccountRepository repository = mock(TradingAccountRepository.class);
        when(repository.findByUserIdAndCurrency(1L, CCY))
                .thenReturn(Optional.of(account(1L, "100")))
                .thenReturn(Optional.of(account(1L, "80")));
        AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-05-29T00:00:00Z"));
        AccountMarginStateCache cache = new AccountMarginStateCache(repository, clock::get);

        Assertions.assertEquals(new BigDecimal("100"), cache.getAccount(1L, CCY).balance());

        // 超过 1s TTL → 过期重新读 DB，拿到新值
        clock.set(Instant.parse("2026-05-29T00:00:02Z"));
        Assertions.assertEquals(new BigDecimal("80"), cache.getAccount(1L, CCY).balance());

        verify(repository, times(2)).findByUserIdAndCurrency(1L, CCY);
    }

    @Test
    void shouldReloadFromDbAfterInvalidate() {
        TradingAccountRepository repository = mock(TradingAccountRepository.class);
        when(repository.findByUserIdAndCurrency(1L, CCY))
                .thenReturn(Optional.of(account(1L, "100")))
                .thenReturn(Optional.of(account(1L, "0")));
        AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-05-29T00:00:00Z"));
        AccountMarginStateCache cache = new AccountMarginStateCache(repository, clock::get);

        Assertions.assertEquals(new BigDecimal("100"), cache.getAccount(1L, CCY).balance());

        // 平仓 / 落账后 invalidate → 即使 TTL 未到也必须重新读 DB
        cache.invalidate(1L);
        Assertions.assertEquals(new BigDecimal("0"), cache.getAccount(1L, CCY).balance());

        verify(repository, times(2)).findByUserIdAndCurrency(1L, CCY);
    }

    @Test
    void shouldReturnNullWhenAccountMissingInDb() {
        TradingAccountRepository repository = mock(TradingAccountRepository.class);
        when(repository.findByUserIdAndCurrency(9L, CCY)).thenReturn(Optional.empty());
        AccountMarginStateCache cache = new AccountMarginStateCache(repository, Instant::now);

        Assertions.assertNull(cache.getAccount(9L, CCY));
    }
}
