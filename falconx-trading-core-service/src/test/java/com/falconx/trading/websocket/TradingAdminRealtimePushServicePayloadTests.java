package com.falconx.trading.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingRiskExposure;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.service.FxRateService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * STAGE-14E2 Task 1：{@link TradingAdminRealtimePushService} 管理端 WS payload 双币硬切单测。
 *
 * <p>对齐 STAGE-14E1 客户端侧 break：admin.position.update 的逐仓 PnL 切片由单币
 * {@code unrealizedPnl}（QC 原币）硬切为双币 + 元数据（quoteCurrency / fxRate /
 * unrealizedPnlInQuote / unrealizedPnlInAccount），口径复用客户端 {@code computeDualPnl}
 * （{@link TradingRealtimeDualPnlSupport}），无并行实现；admin.exposure.update 补 quoteCurrency
 * （来源 SymbolSpec）。无旧单币 {@code unrealizedPnl} 残留由 test-compile 守卫。
 */
class TradingAdminRealtimePushServicePayloadTests {

    private static final String SETTLEMENT = "USDT";

    /** EURAUD ISOLATED：QC=AUD，bid 1.6498，BUY → uPnL(AUD)=-2 → ×fx0.65=-1.30 USDT。 */
    @Test
    void publishPositionPnlUpdates_dualCurrency() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.65000000")));
        MarketSymbolSpecRepository specRepo = Mockito.mock(MarketSymbolSpecRepository.class);
        Mockito.when(specRepo.findByPlatformSymbol("EURAUD")).thenReturn(Optional.of(symbolSpec("AUD")));
        TradingRealtimeDualPnlSupport dualPnl = new TradingRealtimeDualPnlSupport(fx, specRepo, SETTLEMENT);

        TradingUserWebSocketSessionRegistry registry = Mockito.mock(TradingUserWebSocketSessionRegistry.class);
        Mockito.when(registry.broadcastToAdmins(any(), any())).thenReturn(1);
        AdminPositionSummaryAggregator aggregator = new AdminPositionSummaryAggregator();

        TradingAdminRealtimePushService service =
                new TradingAdminRealtimePushService(registry, aggregator, dualPnl);

        TradingQuoteSnapshot quote = eurAudQuote();
        service.publishPositionPnlUpdates("EURAUD",
                List.of(eurAudPosition(TradingMarginMode.ISOLATED)), quote, quote.ts());

        AdminPositionPnlUpdatePayload payload = capturePayload(registry,
                TradingUserWebSocketSessionRegistry.CHANNEL_ADMIN_POSITIONS, AdminPositionPnlUpdatePayload.class);
        Assertions.assertEquals(1, payload.items().size());
        AdminPositionPnlUpdatePayload.Item item = payload.items().get(0);
        Assertions.assertEquals("AUD", item.quoteCurrency(), "quoteCurrency 取自 SymbolSpec");
        Assertions.assertEquals(0, item.fxRate().compareTo(new BigDecimal("0.65")), "fxRate=fx(AUD→USDT)");
        Assertions.assertEquals(0, item.unrealizedPnlInQuote().compareTo(new BigDecimal("-2")),
                "QC 原币 uPnL=-2 AUD");
        Assertions.assertEquals(0, item.unrealizedPnlInAccount().compareTo(new BigDecimal("-1.30")),
                "AC 账户币 uPnL=-1.30 USDT");

        // 平台汇总聚合器写 AC 账户币（跨 symbol 可加），= -1.30
        Assertions.assertEquals(0, aggregator.platformTotalUnrealizedPnl().compareTo(new BigDecimal("-1.30")),
                "summary 聚合 AC 账户币");
    }

    /** FX 不可用：fxRate 降级用 entryFxRate(0.65)，AC=-1.30，不抛（沿 E1/D2）。 */
    @Test
    void publishPositionPnlUpdates_fxUnavailable_degradeEntryFxRate() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.empty());
        MarketSymbolSpecRepository specRepo = Mockito.mock(MarketSymbolSpecRepository.class);
        Mockito.when(specRepo.findByPlatformSymbol("EURAUD")).thenReturn(Optional.of(symbolSpec("AUD")));
        TradingRealtimeDualPnlSupport dualPnl = new TradingRealtimeDualPnlSupport(fx, specRepo, SETTLEMENT);

        TradingUserWebSocketSessionRegistry registry = Mockito.mock(TradingUserWebSocketSessionRegistry.class);
        Mockito.when(registry.broadcastToAdmins(any(), any())).thenReturn(1);

        TradingAdminRealtimePushService service =
                new TradingAdminRealtimePushService(registry, new AdminPositionSummaryAggregator(), dualPnl);

        TradingQuoteSnapshot quote = eurAudQuote();
        service.publishPositionPnlUpdates("EURAUD",
                List.of(eurAudPosition(TradingMarginMode.ISOLATED)), quote, quote.ts());

        AdminPositionPnlUpdatePayload.Item item = capturePayload(registry,
                TradingUserWebSocketSessionRegistry.CHANNEL_ADMIN_POSITIONS, AdminPositionPnlUpdatePayload.class)
                .items().get(0);
        Assertions.assertEquals(0, item.fxRate().compareTo(new BigDecimal("0.65")),
                "FX 不可用降级用 entryFxRate=0.65");
        Assertions.assertEquals(0, item.unrealizedPnlInAccount().compareTo(new BigDecimal("-1.30")),
                "AC 用降级 entryFxRate 算=-1.30");
    }

    /** admin.exposure.update 补 quoteCurrency（来源 SymbolSpec）。 */
    @Test
    void publishExposureUpdate_includesQuoteCurrency() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        MarketSymbolSpecRepository specRepo = Mockito.mock(MarketSymbolSpecRepository.class);
        Mockito.when(specRepo.findByPlatformSymbol("EURAUD")).thenReturn(Optional.of(symbolSpec("AUD")));
        TradingRealtimeDualPnlSupport dualPnl = new TradingRealtimeDualPnlSupport(fx, specRepo, SETTLEMENT);

        TradingUserWebSocketSessionRegistry registry = Mockito.mock(TradingUserWebSocketSessionRegistry.class);
        Mockito.when(registry.broadcastToAdmins(any(), any())).thenReturn(1);

        TradingAdminRealtimePushService service =
                new TradingAdminRealtimePushService(registry, new AdminPositionSummaryAggregator(), dualPnl);

        TradingRiskExposure exposure = new TradingRiskExposure(
                "EURAUD",
                new BigDecimal("10000"),
                new BigDecimal("4000"),
                new BigDecimal("6000"),
                new BigDecimal("9900"),
                OffsetDateTime.now());
        service.publishExposureUpdate(exposure, OffsetDateTime.now());

        AdminExposureUpdatePayload payload = capturePayload(registry,
                TradingUserWebSocketSessionRegistry.CHANNEL_ADMIN_EXPOSURE, AdminExposureUpdatePayload.class);
        Assertions.assertEquals("AUD", payload.quoteCurrency(), "exposure quoteCurrency 取自 SymbolSpec");
    }

    // --------------------------------------------------------------------- helpers

    private static <T> T capturePayload(TradingUserWebSocketSessionRegistry registry,
                                        String channel, Class<T> type) {
        ArgumentCaptor<TradingUserWebSocketEnvelope> captor =
                ArgumentCaptor.forClass(TradingUserWebSocketEnvelope.class);
        Mockito.verify(registry).broadcastToAdmins(eq(channel), captor.capture());
        return type.cast(captor.getValue().data());
    }

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
}
