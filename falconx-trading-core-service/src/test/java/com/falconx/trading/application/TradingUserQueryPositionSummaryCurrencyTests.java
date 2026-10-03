package com.falconx.trading.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.dto.TradingPositionSummaryResponse;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingLedgerRepository;
import com.falconx.trading.repository.TradingLiquidationLogRepository;
import com.falconx.trading.repository.TradingOrderRepository;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.repository.TradingTradeRepository;
import com.falconx.trading.service.FxRateService;
import com.falconx.trading.websocket.TradingRealtimeDualPnlSupport;
import com.falconx.trading.websocket.TradingUserRealtimePayloadFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 多币种与显示一致性收尾（2026-06-03）：{@link TradingUserQueryApplicationService#getPositionSummary}
 * REST 持仓汇总按 AC 账户币求和单测。
 *
 * <p>修复点：原 getPositionSummary 累加 {@code calculatePositionPnl}（QC 原币），跨不同计价币 symbol
 * 直接相加属混币相加；改为经 {@link TradingRealtimeDualPnlSupport#computeDualPnl} 换算 AC 账户币后求和，
 * 与 WS user.position.summary 同口径。
 */
class TradingUserQueryPositionSummaryCurrencyTests {

    private static final String SETTLEMENT = "USDT";
    private static final long USER_ID = 1L;

    /** EURAUD(uPnL=-1.30 USDT) + BTCUSDT(uPnL=+10 USDT) → 总 AC=8.70（旧混币会得 8）；margin 合计 107.25。 */
    @Test
    void getPositionSummary_sumsUnrealizedPnlInAccountCurrency() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.65000000")));
        MarketSymbolSpecRepository specRepo = Mockito.mock(MarketSymbolSpecRepository.class);
        Mockito.when(specRepo.findByPlatformSymbol("EURAUD")).thenReturn(Optional.of(symbolSpec("EURAUD", "AUD")));
        Mockito.when(specRepo.findByPlatformSymbol("BTCUSDT")).thenReturn(Optional.of(symbolSpec("BTCUSDT", "USDT")));

        TradingPositionRepository positionRepo = Mockito.mock(TradingPositionRepository.class);
        Mockito.when(positionRepo.findByUserIdPaginated(eq(USER_ID), any(), eq(0), eq(1000)))
                .thenReturn(List.of(
                        position("EURAUD", new BigDecimal("10000"), "1.6500", "0.65000000"),
                        position("BTCUSDT", new BigDecimal("1"), "50000", "1")));

        TradingQuoteSnapshotRepository quoteRepo = Mockito.mock(TradingQuoteSnapshotRepository.class);
        Mockito.when(quoteRepo.findBySymbol("EURAUD"))
                .thenReturn(Optional.of(quote("EURAUD", "1.6498", "1.6502", "1.6500")));
        Mockito.when(quoteRepo.findBySymbol("BTCUSDT"))
                .thenReturn(Optional.of(quote("BTCUSDT", "50010", "50012", "50011")));

        TradingUserQueryApplicationService service = newService(fx, specRepo, positionRepo, quoteRepo);

        TradingPositionSummaryResponse summary = service.getPositionSummary(USER_ID);

        Assertions.assertEquals(2, summary.openPositionCount());
        Assertions.assertEquals(0, summary.positionsWithoutQuote());
        Assertions.assertEquals(0, summary.totalUnrealizedPnl().compareTo(new BigDecimal("8.70")),
                "总未实现盈亏按 AC 账户币求和 = -1.30 + 10 = 8.70（非混币 8）");
        Assertions.assertEquals(0, summary.totalMarginUsed().compareTo(new BigDecimal("107.25")),
                "保证金合计原样累加（已为 AC 账户币）");
    }

    // --------------------------------------------------------------------- helpers

    private static TradingUserQueryApplicationService newService(FxRateService fx,
                                                                 MarketSymbolSpecRepository specRepo,
                                                                 TradingPositionRepository positionRepo,
                                                                 TradingQuoteSnapshotRepository quoteRepo) {
        TradingRealtimeDualPnlSupport dualPnl = new TradingRealtimeDualPnlSupport(fx, specRepo, SETTLEMENT);
        TradingUserRealtimePayloadFactory payloadFactory =
                new TradingUserRealtimePayloadFactory(quoteRepo, fx, specRepo, SETTLEMENT);
        return new TradingUserQueryApplicationService(
                Mockito.mock(TradingOrderRepository.class),
                Mockito.mock(TradingTradeRepository.class),
                positionRepo,
                Mockito.mock(TradingLedgerRepository.class),
                Mockito.mock(TradingLiquidationLogRepository.class),
                quoteRepo,
                payloadFactory,
                dualPnl,
                Mockito.mock(com.falconx.trading.repository.SymbolLeverageTierRepository.class),
                fx,
                Mockito.mock(com.falconx.trading.config.TradingCoreServiceProperties.class));
    }

    private static TradingQuoteSnapshot quote(String symbol, String bid, String ask, String mark) {
        return new TradingQuoteSnapshot(symbol,
                new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(mark),
                OffsetDateTime.now(), "TEST", false);
    }

    private static SymbolSpec symbolSpec(String symbol, String quoteCurrency) {
        return new SymbolSpec(symbol, null, null, null, null, null, null, null, null,
                null, quoteCurrency, 1);
    }

    private static TradingPosition position(String symbol, BigDecimal qty, String entryPrice, String entryFxRate) {
        return new TradingPosition(
                1L, USER_ID, 1L, symbol,
                TradingOrderSide.BUY,
                qty,
                new BigDecimal(entryPrice),
                entryFxRate == null ? null : new BigDecimal(entryFxRate),
                new BigDecimal("0.005000"),
                1,
                new BigDecimal("200"),
                new BigDecimal("53.625"),
                TradingMarginMode.ISOLATED,
                null,
                null, null, null, null, null,
                TradingPositionStatus.OPEN,
                BigDecimal.ZERO,
                "default",
                BigDecimal.ZERO, BigDecimal.ZERO,
                OffsetDateTime.now(), null, OffsetDateTime.now()
        );
    }
}
