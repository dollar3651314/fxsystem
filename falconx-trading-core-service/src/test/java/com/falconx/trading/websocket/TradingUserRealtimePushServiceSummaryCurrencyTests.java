package com.falconx.trading.websocket;

import static org.mockito.ArgumentMatchers.any;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.application.TradingAccountSnapshotApplicationService;
import com.falconx.trading.application.TradingUserQueryApplicationService;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.service.FxRateService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 多币种与显示一致性收尾（2026-06-03）：{@link TradingUserRealtimePushService#publishPositionPnlUpdates}
 * 用户侧跨 symbol 汇总按 AC 账户币求和单测。
 *
 * <p>修复点：原 {@link UserPositionSummaryAggregator} 累加各 symbol 的 QC 原币 unrealizedPnl，
 * 跨不同计价币 symbol 直接相加属混币相加（B 阶段已知偏差）。改为累加
 * {@link TradingRealtimeDualPnlSupport#computeDualPnl} 的 AC 账户币 {@code inAccount}，
 * 口径与 admin {@link TradingAdminRealtimePushService} 完全一致。
 */
class TradingUserRealtimePushServiceSummaryCurrencyTests {

    private static final String SETTLEMENT = "USDT";
    private static final long USER_ID = 1L;

    /**
     * 同一用户两品种：EURAUD(QC=AUD, uPnL=-2 AUD ×fx0.65=-1.30 USDT) + BTCUSDT(QC=USDT, uPnL=+10 USDT)。
     * 正确 AC 汇总 = -1.30 + 10 = 8.70；旧混币相加会得 -2 + 10 = 8。
     */
    @Test
    void totalForUser_sumsInAccountAcrossSymbols() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.65000000")));
        MarketSymbolSpecRepository specRepo = Mockito.mock(MarketSymbolSpecRepository.class);
        Mockito.when(specRepo.findByPlatformSymbol("EURAUD")).thenReturn(Optional.of(symbolSpec("EURAUD", "AUD")));
        Mockito.when(specRepo.findByPlatformSymbol("BTCUSDT")).thenReturn(Optional.of(symbolSpec("BTCUSDT", "USDT")));

        UserPositionSummaryAggregator aggregator = new UserPositionSummaryAggregator();
        TradingUserRealtimePushService service = newService(fx, specRepo, aggregator);

        TradingQuoteSnapshot eurAud = quote("EURAUD", "1.6498", "1.6502", "1.6500");
        TradingQuoteSnapshot btcUsdt = quote("BTCUSDT", "50010", "50012", "50011");

        // BUY EURAUD 10000 @1.6500，bid 1.6498 → uPnL=-2 AUD
        service.publishPositionPnlUpdates(
                List.of(position("EURAUD", new BigDecimal("10000"), "1.6500", "0.65000000")),
                eurAud.bid(), eurAud);
        // BUY BTCUSDT 1 @50000，bid 50010 → uPnL=+10 USDT
        service.publishPositionPnlUpdates(
                List.of(position("BTCUSDT", new BigDecimal("1"), "50000", "1")),
                btcUsdt.bid(), btcUsdt);

        Assertions.assertEquals(0, aggregator.totalForUser(USER_ID).compareTo(new BigDecimal("8.70")),
                "跨 symbol 汇总按 AC 账户币求和 = -1.30 + 10 = 8.70（非混币 8）");
    }

    /**
     * FX 与开仓冻结 entryFxRate 均不可用 → 该 symbol inAccount 为 null，剔除出汇总（镜像 admin），
     * 只保留可换算的 BTCUSDT(+10)。
     */
    @Test
    void totalForUser_skipsPositionsWithoutConvertibleAccountAmount() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.empty());
        MarketSymbolSpecRepository specRepo = Mockito.mock(MarketSymbolSpecRepository.class);
        Mockito.when(specRepo.findByPlatformSymbol("EURAUD")).thenReturn(Optional.of(symbolSpec("EURAUD", "AUD")));
        Mockito.when(specRepo.findByPlatformSymbol("BTCUSDT")).thenReturn(Optional.of(symbolSpec("BTCUSDT", "USDT")));

        UserPositionSummaryAggregator aggregator = new UserPositionSummaryAggregator();
        TradingUserRealtimePushService service = newService(fx, specRepo, aggregator);

        TradingQuoteSnapshot eurAud = quote("EURAUD", "1.6498", "1.6502", "1.6500");
        TradingQuoteSnapshot btcUsdt = quote("BTCUSDT", "50010", "50012", "50011");

        // EURAUD 仓 entryFxRate=null → FX 不可用且无冻结汇率 → inAccount null → 不计入
        service.publishPositionPnlUpdates(
                List.of(position("EURAUD", new BigDecimal("10000"), "1.6500", null)),
                eurAud.bid(), eurAud);
        service.publishPositionPnlUpdates(
                List.of(position("BTCUSDT", new BigDecimal("1"), "50000", "1")),
                btcUsdt.bid(), btcUsdt);

        Assertions.assertEquals(0, aggregator.totalForUser(USER_ID).compareTo(new BigDecimal("10")),
                "AC 不可换算的 EURAUD 仓剔除，仅 BTCUSDT +10 计入");
    }

    // --------------------------------------------------------------------- helpers

    private static TradingUserRealtimePushService newService(FxRateService fx,
                                                             MarketSymbolSpecRepository specRepo,
                                                             UserPositionSummaryAggregator aggregator) {
        TradingRealtimeDualPnlSupport dualPnl = new TradingRealtimeDualPnlSupport(fx, specRepo, SETTLEMENT);
        TradingQuoteSnapshotRepository quoteRepo = Mockito.mock(TradingQuoteSnapshotRepository.class);
        TradingUserRealtimePayloadFactory payloadFactory =
                new TradingUserRealtimePayloadFactory(quoteRepo, fx, specRepo, SETTLEMENT);
        TradingUserWebSocketSessionRegistry registry = Mockito.mock(TradingUserWebSocketSessionRegistry.class);
        Mockito.when(registry.broadcast(any(), any(), any())).thenReturn(0);
        return new TradingUserRealtimePushService(
                registry,
                Mockito.mock(TradingAccountSnapshotApplicationService.class),
                Mockito.mock(TradingUserQueryApplicationService.class),
                payloadFactory,
                aggregator,
                new UserPositionSummaryPushThrottler(),
                dualPnl);
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
