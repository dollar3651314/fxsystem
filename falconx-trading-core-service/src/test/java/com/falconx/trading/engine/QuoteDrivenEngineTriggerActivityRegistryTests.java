package com.falconx.trading.engine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingNotificationApplicationService;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.application.TradingPendingOrderApplicationService;
import com.falconx.trading.application.TradingPositionCloseApplicationService;
import com.falconx.trading.application.TradingPriceAlertApplicationService;
import com.falconx.trading.entity.TradingQuoteQualityStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.repository.RedisTradingRiskSwitchCache;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.TradingPriceAlertRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.repository.TradingRiskExposureRepository;
import com.falconx.trading.service.TradingRiskObservabilityService;
import com.falconx.trading.service.TradingScheduleService;
import com.falconx.trading.websocket.AdminExposurePushThrottler;
import com.falconx.trading.websocket.AdminPositionPushThrottler;
import com.falconx.trading.websocket.AdminPositionSummaryPushThrottler;
import com.falconx.trading.websocket.PositionPnlPushThrottler;
import com.falconx.trading.websocket.TradingAdminRealtimePushService;
import com.falconx.trading.websocket.TradingUserRealtimePushService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QuoteDrivenEngineTriggerActivityRegistryTests {

    @Test
    void shouldSkipPendingOrderAndPriceAlertDatabaseScansWhenSymbolHasNoActiveTriggers() {
        TradingQuoteSnapshotRepository quoteRepository = mock(TradingQuoteSnapshotRepository.class);
        OpenPositionSnapshotStore openPositionSnapshotStore = mock(OpenPositionSnapshotStore.class);
        PositionTriggerRuleEvaluator positionTriggerRuleEvaluator = mock(PositionTriggerRuleEvaluator.class);
        TradingPositionCloseApplicationService positionCloseApplicationService =
                mock(TradingPositionCloseApplicationService.class);
        TradingRiskObservabilityService riskObservabilityService = mock(TradingRiskObservabilityService.class);
        TradingScheduleService scheduleService = mock(TradingScheduleService.class);
        RedisTradingRiskSwitchCache riskSwitchCache = mock(RedisTradingRiskSwitchCache.class);
        TradingUserRealtimePushService userRealtimePushService = mock(TradingUserRealtimePushService.class);
        PositionPnlPushThrottler pnlPushThrottler = mock(PositionPnlPushThrottler.class);
        TradingAdminRealtimePushService adminRealtimePushService = mock(TradingAdminRealtimePushService.class);
        AdminExposurePushThrottler exposurePushThrottler = mock(AdminExposurePushThrottler.class);
        AdminPositionPushThrottler adminPositionPushThrottler = mock(AdminPositionPushThrottler.class);
        AdminPositionSummaryPushThrottler adminPositionSummaryPushThrottler =
                mock(AdminPositionSummaryPushThrottler.class);
        TradingRiskExposureRepository riskExposureRepository = mock(TradingRiskExposureRepository.class);
        TradingPendingOrderTriggerRepository pendingOrderRepository =
                mock(TradingPendingOrderTriggerRepository.class);
        PendingOrderTriggerEvaluator pendingOrderTriggerEvaluator = mock(PendingOrderTriggerEvaluator.class);
        TradingPendingOrderApplicationService pendingOrderApplicationService =
                mock(TradingPendingOrderApplicationService.class);
        TradingOrderPlacementApplicationService orderPlacementApplicationService =
                mock(TradingOrderPlacementApplicationService.class);
        TradingPriceAlertRepository priceAlertRepository = mock(TradingPriceAlertRepository.class);
        PriceAlertEvaluator priceAlertEvaluator = mock(PriceAlertEvaluator.class);
        TradingPriceAlertApplicationService priceAlertApplicationService =
                mock(TradingPriceAlertApplicationService.class);
        SymbolTriggerActivityRegistry triggerActivityRegistry = new SymbolTriggerActivityRegistry();
        AccountMarginEvaluator accountMarginEvaluator = mock(AccountMarginEvaluator.class);
        AccountMarginStateCache accountMarginStateCache = mock(AccountMarginStateCache.class);
        TradingNotificationApplicationService notificationService = mock(TradingNotificationApplicationService.class);

        QuoteDrivenEngine engine = new QuoteDrivenEngine(
                quoteRepository,
                openPositionSnapshotStore,
                positionTriggerRuleEvaluator,
                positionCloseApplicationService,
                riskObservabilityService,
                scheduleService,
                riskSwitchCache,
                userRealtimePushService,
                pnlPushThrottler,
                adminRealtimePushService,
                exposurePushThrottler,
                adminPositionPushThrottler,
                adminPositionSummaryPushThrottler,
                riskExposureRepository,
                pendingOrderRepository,
                pendingOrderTriggerEvaluator,
                pendingOrderApplicationService,
                orderPlacementApplicationService,
                priceAlertRepository,
                priceAlertEvaluator,
                priceAlertApplicationService,
                triggerActivityRegistry,
                accountMarginEvaluator,
                accountMarginStateCache,
                notificationService,
                // STAGE-14C2 Task 6：FX_PAUSED 按类目强平依赖；本类不测 pause，默认 hasActiveGlobalPause=false
                mock(com.falconx.trading.repository.TradingRiskControlActionRepository.class),
                mock(com.falconx.trading.repository.MarketSymbolSpecRepository.class),
                mock(com.falconx.trading.repository.FxPauseBehaviorRepository.class),
                mock(com.falconx.trading.application.CrossLiquidationOrchestrator.class)
        );
        OffsetDateTime ts = OffsetDateTime.parse("2026-05-24T08:00:00Z");
        TradingQuoteSnapshot snapshot = new TradingQuoteSnapshot(
                "EURUSD",
                new BigDecimal("1.0800"),
                new BigDecimal("1.0802"),
                new BigDecimal("1.0801"),
                ts,
                "TM_QUOTE",
                false,
                TradingQuoteQualityStatus.FRESH,
                null
        );
        when(quoteRepository.save(any())).thenReturn(snapshot);
        when(scheduleService.isOpenAllowed(eq("EURUSD"), any())).thenReturn(true);
        when(openPositionSnapshotStore.listOpenBySymbol("EURUSD")).thenReturn(List.of());

        engine.processTick(new MarketPriceTickEventPayload(
                "EURUSD",
                new BigDecimal("1.0800"),
                new BigDecimal("1.0802"),
                new BigDecimal("1.0801"),
                new BigDecimal("1.0801"),
                ts,
                "TM_QUOTE",
                false
        ));

        verify(pendingOrderRepository, never()).findPendingBySymbol("EURUSD");
        verify(priceAlertRepository, never()).findTriggerableBySymbol(eq("EURUSD"), any());
    }

    @Test
    void shouldUseSavedQuoteSnapshotWithoutRedisReadBackOnTick() {
        TradingQuoteSnapshotRepository quoteRepository = mock(TradingQuoteSnapshotRepository.class);
        OpenPositionSnapshotStore openPositionSnapshotStore = mock(OpenPositionSnapshotStore.class);
        PositionTriggerRuleEvaluator positionTriggerRuleEvaluator = mock(PositionTriggerRuleEvaluator.class);
        TradingPositionCloseApplicationService positionCloseApplicationService =
                mock(TradingPositionCloseApplicationService.class);
        TradingRiskObservabilityService riskObservabilityService = mock(TradingRiskObservabilityService.class);
        TradingScheduleService scheduleService = mock(TradingScheduleService.class);
        RedisTradingRiskSwitchCache riskSwitchCache = mock(RedisTradingRiskSwitchCache.class);
        TradingUserRealtimePushService userRealtimePushService = mock(TradingUserRealtimePushService.class);
        PositionPnlPushThrottler pnlPushThrottler = mock(PositionPnlPushThrottler.class);
        TradingAdminRealtimePushService adminRealtimePushService = mock(TradingAdminRealtimePushService.class);
        AdminExposurePushThrottler exposurePushThrottler = mock(AdminExposurePushThrottler.class);
        AdminPositionPushThrottler adminPositionPushThrottler = mock(AdminPositionPushThrottler.class);
        AdminPositionSummaryPushThrottler adminPositionSummaryPushThrottler =
                mock(AdminPositionSummaryPushThrottler.class);
        TradingRiskExposureRepository riskExposureRepository = mock(TradingRiskExposureRepository.class);
        TradingPendingOrderTriggerRepository pendingOrderRepository =
                mock(TradingPendingOrderTriggerRepository.class);
        PendingOrderTriggerEvaluator pendingOrderTriggerEvaluator = mock(PendingOrderTriggerEvaluator.class);
        TradingPendingOrderApplicationService pendingOrderApplicationService =
                mock(TradingPendingOrderApplicationService.class);
        TradingOrderPlacementApplicationService orderPlacementApplicationService =
                mock(TradingOrderPlacementApplicationService.class);
        TradingPriceAlertRepository priceAlertRepository = mock(TradingPriceAlertRepository.class);
        PriceAlertEvaluator priceAlertEvaluator = mock(PriceAlertEvaluator.class);
        TradingPriceAlertApplicationService priceAlertApplicationService =
                mock(TradingPriceAlertApplicationService.class);
        SymbolTriggerActivityRegistry triggerActivityRegistry = new SymbolTriggerActivityRegistry();
        AccountMarginEvaluator accountMarginEvaluator = mock(AccountMarginEvaluator.class);
        AccountMarginStateCache accountMarginStateCache = mock(AccountMarginStateCache.class);
        TradingNotificationApplicationService notificationService = mock(TradingNotificationApplicationService.class);

        QuoteDrivenEngine engine = new QuoteDrivenEngine(
                quoteRepository,
                openPositionSnapshotStore,
                positionTriggerRuleEvaluator,
                positionCloseApplicationService,
                riskObservabilityService,
                scheduleService,
                riskSwitchCache,
                userRealtimePushService,
                pnlPushThrottler,
                adminRealtimePushService,
                exposurePushThrottler,
                adminPositionPushThrottler,
                adminPositionSummaryPushThrottler,
                riskExposureRepository,
                pendingOrderRepository,
                pendingOrderTriggerEvaluator,
                pendingOrderApplicationService,
                orderPlacementApplicationService,
                priceAlertRepository,
                priceAlertEvaluator,
                priceAlertApplicationService,
                triggerActivityRegistry,
                accountMarginEvaluator,
                accountMarginStateCache,
                notificationService,
                // STAGE-14C2 Task 6：FX_PAUSED 按类目强平依赖；本类不测 pause，默认 hasActiveGlobalPause=false
                mock(com.falconx.trading.repository.TradingRiskControlActionRepository.class),
                mock(com.falconx.trading.repository.MarketSymbolSpecRepository.class),
                mock(com.falconx.trading.repository.FxPauseBehaviorRepository.class),
                mock(com.falconx.trading.application.CrossLiquidationOrchestrator.class)
        );
        OffsetDateTime ts = OffsetDateTime.parse("2026-05-24T08:00:00Z");
        TradingQuoteSnapshot snapshot = new TradingQuoteSnapshot(
                "EURUSD",
                new BigDecimal("1.0800"),
                new BigDecimal("1.0802"),
                new BigDecimal("1.0801"),
                ts,
                "TM_QUOTE",
                false,
                TradingQuoteQualityStatus.FRESH,
                null
        );
        when(quoteRepository.save(any())).thenReturn(snapshot);
        when(quoteRepository.findBySymbol("EURUSD")).thenReturn(Optional.of(snapshot));
        when(scheduleService.isOpenAllowed(eq("EURUSD"), any())).thenReturn(true);
        when(openPositionSnapshotStore.listOpenBySymbol("EURUSD")).thenReturn(List.of());

        engine.processTick(new MarketPriceTickEventPayload(
                "EURUSD",
                new BigDecimal("1.0800"),
                new BigDecimal("1.0802"),
                new BigDecimal("1.0801"),
                new BigDecimal("1.0801"),
                ts,
                "TM_QUOTE",
                false
        ));

        verify(quoteRepository, never()).findBySymbol("EURUSD");
    }
}
