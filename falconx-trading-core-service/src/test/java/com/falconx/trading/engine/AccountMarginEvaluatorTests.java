package com.falconx.trading.engine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.entity.MarginLevelStatus;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.service.AccountEquityCalculator;
import com.falconx.trading.service.MarginLevelMonitor;
import com.falconx.trading.service.model.AccountMarginState;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@link AccountMarginEvaluator} 单元测试（STAGE-14C1 Task 9）。
 *
 * <p>锁定单仓 MarginLevel 实时重算 + StopOut 判定职责：
 * <ul>
 *   <li>StopOut（ML ≤ 30%）→ 返回 true（caller 强平）。</li>
 *   <li>MarginCall（100% ≥ ML &gt; 30%）→ 返回 false（Monitor 内已发告警，不强平）。</li>
 *   <li>HEALTHY → false。</li>
 *   <li>FX 不可用（marginLevel=null）→ false（不因 MarginLevel 强平，liqPrice 仍独立生效）。</li>
 *   <li>账户缺失 → false（跳过判定）。</li>
 * </ul>
 */
class AccountMarginEvaluatorTests {

    private static final String AC = "USDT";

    private TradingPosition position(long userId, String symbol) {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-29T00:00:00Z");
        return new TradingPosition(
                1L, 2L, userId, symbol, TradingOrderSide.BUY,
                new BigDecimal("1.00000000"), new BigDecimal("1.50000000"), BigDecimal.ONE,
                new BigDecimal("0.030000"), 1, new BigDecimal("10"), new BigDecimal("1000.00000000"),
                TradingMarginMode.ISOLATED, null, null, null, null, null, null,
                TradingPositionStatus.OPEN, BigDecimal.ZERO, "default",
                BigDecimal.ZERO, BigDecimal.ZERO, now, null, now);
    }

    private TradingAccount account(long userId) {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-29T00:00:00Z");
        return new TradingAccount(900L, userId, AC, new BigDecimal("1000"),
                BigDecimal.ZERO, BigDecimal.ZERO, TradingMarginMode.ISOLATED, null, null, now, now);
    }

    private AccountMarginEvaluator newEvaluator(AccountMarginStateCache cache,
                                                AccountEquityCalculator calculator,
                                                MarginLevelMonitor monitor,
                                                MarketSymbolSpecRepository specRepo) {
        return new AccountMarginEvaluator(cache, calculator, monitor, specRepo, "USDT");
    }

    @Test
    void shouldReturnStopOutTrueWhenMarginLevelBreaches() {
        AccountMarginStateCache cache = mock(AccountMarginStateCache.class);
        AccountEquityCalculator calculator = mock(AccountEquityCalculator.class);
        MarginLevelMonitor monitor = mock(MarginLevelMonitor.class);
        MarketSymbolSpecRepository specRepo = mock(MarketSymbolSpecRepository.class);

        TradingPosition pos = position(1L, "EURAUD");
        AccountMarginState state = new AccountMarginState(new BigDecimal("12"), new BigDecimal("45"), new BigDecimal("26.67"));
        when(cache.getAccount(1L, AC)).thenReturn(account(1L));
        when(specRepo.findByPlatformSymbol("EURAUD")).thenReturn(Optional.of(spec("EUR", "AUD")));
        when(calculator.computePositionMarginLevel(eq(pos), any(), eq(new BigDecimal("1.40")), eq("AUD"))).thenReturn(state);
        when(monitor.evaluate(1L, state)).thenReturn(MarginLevelStatus.STOP_OUT);

        AccountMarginEvaluator evaluator = newEvaluator(cache, calculator, monitor, specRepo);
        BigDecimal stopOut = evaluator.stopOutMarginLevel(pos, new BigDecimal("1.40"));

        // 触发 StopOut → 返回触发时的真实 marginLevel（供通知展示），非 null 即触发
        Assertions.assertEquals(new BigDecimal("26.67"), stopOut);
    }

    @Test
    void shouldReturnFalseAndNotStopOutOnMarginCall() {
        AccountMarginStateCache cache = mock(AccountMarginStateCache.class);
        AccountEquityCalculator calculator = mock(AccountEquityCalculator.class);
        MarginLevelMonitor monitor = mock(MarginLevelMonitor.class);
        MarketSymbolSpecRepository specRepo = mock(MarketSymbolSpecRepository.class);

        TradingPosition pos = position(1L, "EURAUD");
        AccountMarginState state = new AccountMarginState(new BigDecimal("30"), new BigDecimal("45"), new BigDecimal("66.67"));
        when(cache.getAccount(1L, AC)).thenReturn(account(1L));
        when(specRepo.findByPlatformSymbol("EURAUD")).thenReturn(Optional.of(spec("EUR", "AUD")));
        when(calculator.computePositionMarginLevel(eq(pos), any(), any(), eq("AUD"))).thenReturn(state);
        when(monitor.evaluate(1L, state)).thenReturn(MarginLevelStatus.MARGIN_CALL);

        AccountMarginEvaluator evaluator = newEvaluator(cache, calculator, monitor, specRepo);
        BigDecimal stopOut = evaluator.stopOutMarginLevel(pos, new BigDecimal("1.40"));

        // MarginCall 不触发 StopOut → 返回 null
        Assertions.assertNull(stopOut);
        // Monitor 内部负责 MarginCall 告警 + 节流；evaluator 必须调用 monitor.evaluate（驱动告警）
        verify(monitor).evaluate(1L, state);
    }

    @Test
    void shouldReturnFalseWhenFxDegradedMarginLevelNull() {
        AccountMarginStateCache cache = mock(AccountMarginStateCache.class);
        AccountEquityCalculator calculator = mock(AccountEquityCalculator.class);
        MarginLevelMonitor monitor = mock(MarginLevelMonitor.class);
        MarketSymbolSpecRepository specRepo = mock(MarketSymbolSpecRepository.class);

        TradingPosition pos = position(1L, "EURAUD");
        AccountMarginState degraded = new AccountMarginState(null, new BigDecimal("45"), null);
        when(cache.getAccount(1L, AC)).thenReturn(account(1L));
        when(specRepo.findByPlatformSymbol("EURAUD")).thenReturn(Optional.of(spec("EUR", "AUD")));
        when(calculator.computePositionMarginLevel(eq(pos), any(), any(), eq("AUD"))).thenReturn(degraded);
        when(monitor.evaluate(1L, degraded)).thenReturn(MarginLevelStatus.HEALTHY);

        AccountMarginEvaluator evaluator = newEvaluator(cache, calculator, monitor, specRepo);

        Assertions.assertNull(evaluator.stopOutMarginLevel(pos, new BigDecimal("1.40")));
    }

    @Test
    void shouldReturnFalseAndSkipWhenAccountMissing() {
        AccountMarginStateCache cache = mock(AccountMarginStateCache.class);
        AccountEquityCalculator calculator = mock(AccountEquityCalculator.class);
        MarginLevelMonitor monitor = mock(MarginLevelMonitor.class);
        MarketSymbolSpecRepository specRepo = mock(MarketSymbolSpecRepository.class);

        TradingPosition pos = position(7L, "EURAUD");
        when(cache.getAccount(7L, AC)).thenReturn(null);

        AccountMarginEvaluator evaluator = newEvaluator(cache, calculator, monitor, specRepo);

        Assertions.assertNull(evaluator.stopOutMarginLevel(pos, new BigDecimal("1.40")));
        // 账户缺失直接跳过，不触碰 calculator / monitor
        verify(calculator, never()).computePositionMarginLevel(any(), any(), any(), any());
        verify(monitor, never()).evaluate(any(), any());
    }

    private SymbolSpec spec(String base, String quote) {
        return new SymbolSpec(
                "EURAUD", 200, new BigDecimal("0.0002"), new BigDecimal("0.00010"),
                new BigDecimal("0.01"), new BigDecimal("100000"), new BigDecimal("10"),
                5, 2, base, quote, 2);
    }
}
