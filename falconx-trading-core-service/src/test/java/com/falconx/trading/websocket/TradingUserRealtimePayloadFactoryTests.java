package com.falconx.trading.websocket;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.dto.TradingPositionItemResponse;
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
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * STAGE-14E1 Task 1：{@link TradingUserRealtimePayloadFactory} 双币 position.update / position.pnl payload 单测。
 *
 * <p>master §7.5 WebSocket 最终 break：position 推送由单币 {@code unrealizedPnl}（QC 原币）硬切为
 * 双币 + 元数据（{@code quoteCurrency / fxRate / unrealizedPnlInQuote / unrealizedPnlInAccount / isolatedMargin}）。
 * 口径复用 STAGE-14B/D2 {@code calculatePositionPnlInAccount}：QC→AC 用实时 {@link FxRateService}，
 * FX 不可用降级开仓冻结 {@code entryFxRate}（不抛）。
 */
class TradingUserRealtimePayloadFactoryTests {

    private static final String SETTLEMENT = "USDT";

    /** EURAUD ISOLATED 仓：QC=AUD，bid 1.6498，BUY → uPnL(AUD)=(1.6498-1.6500)*10000=-2 → ×fx0.65=-1.30 USDT。 */
    @Test
    void toPositionPayload_eurAudIsolated_dualCurrency() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.65000000")));
        TradingQuoteSnapshotRepository quoteRepo = Mockito.mock(TradingQuoteSnapshotRepository.class);
        Mockito.when(quoteRepo.findBySymbol("EURAUD")).thenReturn(Optional.of(eurAudQuote()));
        MarketSymbolSpecRepository specRepo = Mockito.mock(MarketSymbolSpecRepository.class);
        Mockito.when(specRepo.findByPlatformSymbol("EURAUD")).thenReturn(Optional.of(symbolSpec("AUD")));

        TradingUserRealtimePayloadFactory factory =
                new TradingUserRealtimePayloadFactory(quoteRepo, fx, specRepo, SETTLEMENT);

        TradingPositionItemResponse payload = factory.toPositionPayload(eurAudPosition(TradingMarginMode.ISOLATED));

        Assertions.assertEquals("AUD", payload.quoteCurrency(), "quoteCurrency 取自 SymbolSpec");
        Assertions.assertNotNull(payload.fxRate(), "fxRate 非空");
        Assertions.assertEquals(0, payload.fxRate().compareTo(new BigDecimal("0.65")), "fxRate=fx(AUD→USDT)");
        Assertions.assertEquals(0, payload.unrealizedPnlInQuote().compareTo(new BigDecimal("-2")),
                "QC 原币 uPnL=-2 AUD");
        Assertions.assertEquals(0, payload.unrealizedPnlInAccount().compareTo(new BigDecimal("-1.30")),
                "AC 账户币 uPnL=-1.30 USDT");
        Assertions.assertNotNull(payload.isolatedMargin(), "ISOLATED 仓 isolatedMargin 非空");
        Assertions.assertEquals(0, payload.isolatedMargin().compareTo(new BigDecimal("53.625")),
                "isolatedMargin==position.margin");
        Assertions.assertNotNull(payload.liquidationPrice(), "ISOLATED 仓 liquidationPrice 非空");
    }

    /** CROSS 仓：isolatedMargin==null（margin 不归属单仓）；liquidationPrice CROSS 仓本就 null。 */
    @Test
    void toPositionPayload_cross_isolatedMarginNull() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.65000000")));
        TradingQuoteSnapshotRepository quoteRepo = Mockito.mock(TradingQuoteSnapshotRepository.class);
        Mockito.when(quoteRepo.findBySymbol("EURAUD")).thenReturn(Optional.of(eurAudQuote()));
        MarketSymbolSpecRepository specRepo = Mockito.mock(MarketSymbolSpecRepository.class);
        Mockito.when(specRepo.findByPlatformSymbol("EURAUD")).thenReturn(Optional.of(symbolSpec("AUD")));

        TradingUserRealtimePayloadFactory factory =
                new TradingUserRealtimePayloadFactory(quoteRepo, fx, specRepo, SETTLEMENT);

        TradingPositionItemResponse payload = factory.toPositionPayload(crossPosition());

        Assertions.assertNull(payload.isolatedMargin(), "CROSS 仓 isolatedMargin==null");
        Assertions.assertNull(payload.liquidationPrice(), "CROSS 仓 liquidationPrice==null");
        Assertions.assertEquals("AUD", payload.quoteCurrency());
        Assertions.assertNotNull(payload.unrealizedPnlInAccount(), "双币仍照常算");
    }

    /** FX 不可用：fxRate 降级用 entryFxRate(0.65)，unrealizedPnlInAccount 用降级算，不抛。 */
    @Test
    void toPositionPayload_fxUnavailable_degradeEntryFxRate() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.empty());
        TradingQuoteSnapshotRepository quoteRepo = Mockito.mock(TradingQuoteSnapshotRepository.class);
        Mockito.when(quoteRepo.findBySymbol("EURAUD")).thenReturn(Optional.of(eurAudQuote()));
        MarketSymbolSpecRepository specRepo = Mockito.mock(MarketSymbolSpecRepository.class);
        Mockito.when(specRepo.findByPlatformSymbol("EURAUD")).thenReturn(Optional.of(symbolSpec("AUD")));

        TradingUserRealtimePayloadFactory factory =
                new TradingUserRealtimePayloadFactory(quoteRepo, fx, specRepo, SETTLEMENT);

        TradingPositionItemResponse payload = factory.toPositionPayload(eurAudPosition(TradingMarginMode.ISOLATED));

        Assertions.assertEquals(0, payload.fxRate().compareTo(new BigDecimal("0.65")),
                "FX 不可用降级用 entryFxRate=0.65");
        Assertions.assertEquals(0, payload.unrealizedPnlInQuote().compareTo(new BigDecimal("-2")),
                "QC 原币 uPnL 仍算");
        Assertions.assertEquals(0, payload.unrealizedPnlInAccount().compareTo(new BigDecimal("-1.30")),
                "AC 用降级 entryFxRate 算=-1.30");
    }

    /** toPnlPayload 同样含双币字段。 */
    @Test
    void toPnlPayload_dualCurrency() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.65000000")));
        TradingQuoteSnapshotRepository quoteRepo = Mockito.mock(TradingQuoteSnapshotRepository.class);
        MarketSymbolSpecRepository specRepo = Mockito.mock(MarketSymbolSpecRepository.class);
        Mockito.when(specRepo.findByPlatformSymbol("EURAUD")).thenReturn(Optional.of(symbolSpec("AUD")));

        TradingUserRealtimePayloadFactory factory =
                new TradingUserRealtimePayloadFactory(quoteRepo, fx, specRepo, SETTLEMENT);

        TradingQuoteSnapshot quote = eurAudQuote();
        BigDecimal mark = new BigDecimal("1.6498");
        TradingPositionPnlUpdatePayload payload =
                factory.toPnlPayload(eurAudPosition(TradingMarginMode.ISOLATED), mark, quote);

        Assertions.assertEquals("AUD", payload.quoteCurrency());
        Assertions.assertEquals(0, payload.fxRate().compareTo(new BigDecimal("0.65")));
        Assertions.assertEquals(0, payload.unrealizedPnlInQuote().compareTo(new BigDecimal("-2")));
        Assertions.assertEquals(0, payload.unrealizedPnlInAccount().compareTo(new BigDecimal("-1.30")));
        Assertions.assertNotNull(payload.isolatedMargin(), "ISOLATED 仓 isolatedMargin 非空");
    }

    // --------------------------------------------------------------------- helpers

    private static TradingQuoteSnapshot eurAudQuote() {
        return new TradingQuoteSnapshot("EURAUD",
                new BigDecimal("1.6498"),   // bid (BUY 平仓走 bid)
                new BigDecimal("1.6502"),   // ask
                new BigDecimal("1.6500"),   // mark
                OffsetDateTime.now(), "TEST", false);
    }

    private static SymbolSpec symbolSpec(String quoteCurrency) {
        return new SymbolSpec("EURAUD", null, null, null, null, null, null, null, null,
                "EUR", quoteCurrency, 1);
    }

    private static TradingPosition eurAudPosition(TradingMarginMode mode) {
        return new TradingPosition(
                1L, 1L, 1L, "EURAUD",
                TradingOrderSide.BUY,
                new BigDecimal("10000"),
                new BigDecimal("1.6500"),
                new BigDecimal("0.65000000"),   // entryFxRate (AUD→USDT)
                new BigDecimal("0.005000"),
                1,
                new BigDecimal("200"),
                new BigDecimal("53.625"),       // margin (AC)
                mode,
                mode == TradingMarginMode.ISOLATED ? new BigDecimal("1.6000") : null,
                null, null, null, null, null,
                TradingPositionStatus.OPEN,
                BigDecimal.ZERO,
                "default",
                BigDecimal.ZERO, BigDecimal.ZERO,
                OffsetDateTime.now(), null, OffsetDateTime.now()
        );
    }

    private static TradingPosition crossPosition() {
        return eurAudPosition(TradingMarginMode.CROSS);
    }
}
