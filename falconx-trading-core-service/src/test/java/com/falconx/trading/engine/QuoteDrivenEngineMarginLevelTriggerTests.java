package com.falconx.trading.engine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingNotificationApplicationService;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.application.TradingPendingOrderApplicationService;
import com.falconx.trading.application.TradingPositionCloseApplicationService;
import com.falconx.trading.application.TradingPriceAlertApplicationService;
import com.falconx.trading.dto.PositionCloseResult;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionCloseReason;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.entity.TradingQuoteQualityStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.entity.FxPauseBehavior;
import com.falconx.trading.repository.FxPauseBehaviorRepository;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.RedisTradingRiskSwitchCache;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.TradingRiskControlActionRepository;
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
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * QuoteDrivenEngine MarginLevel / StopOut 双触发单元测试（STAGE-14C1 Task 9）。
 *
 * <p>锁定 master §6.3 ISOLATED 双触发：liqPrice 命中 <b>或</b> 单仓 MarginLevel ≤ stopOut，任一即强平；
 * 同一 tick 同一仓位只强平一次；MarginCall 不强平；FX 降级不因 MarginLevel 强平；StopOut 强平后发
 * {@code STOP_OUT_TRIGGERED} 通知 + 失效账户缓存。
 */
class QuoteDrivenEngineMarginLevelTriggerTests {

    private static final String SYMBOL = "EURAUD";

    /** 测试夹具：把所有 mock 依赖打包，便于多用例复用。 */
    private record Fixture(QuoteDrivenEngine engine,
                           TradingQuoteSnapshotRepository quoteRepository,
                           OpenPositionSnapshotStore openPositionSnapshotStore,
                           PositionTriggerRuleEvaluator positionTriggerRuleEvaluator,
                           TradingPositionCloseApplicationService positionCloseApplicationService,
                           AccountMarginEvaluator accountMarginEvaluator,
                           AccountMarginStateCache accountMarginStateCache,
                           TradingNotificationApplicationService notificationService,
                           TradingScheduleService scheduleService,
                           RedisTradingRiskSwitchCache riskSwitchCache,
                           SymbolTriggerActivityRegistry triggerActivityRegistry,
                           TradingRiskControlActionRepository riskControlActionRepository,
                           MarketSymbolSpecRepository marketSymbolSpecRepository,
                           FxPauseBehaviorRepository fxPauseBehaviorRepository) {
    }

    private Fixture newFixture() {
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
        TradingRiskControlActionRepository riskControlActionRepository =
                mock(TradingRiskControlActionRepository.class);
        MarketSymbolSpecRepository marketSymbolSpecRepository = mock(MarketSymbolSpecRepository.class);
        FxPauseBehaviorRepository fxPauseBehaviorRepository = mock(FxPauseBehaviorRepository.class);

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
                riskControlActionRepository,
                marketSymbolSpecRepository,
                fxPauseBehaviorRepository,
                mock(com.falconx.trading.application.CrossLiquidationOrchestrator.class)
        );
        return new Fixture(engine, quoteRepository, openPositionSnapshotStore, positionTriggerRuleEvaluator,
                positionCloseApplicationService, accountMarginEvaluator, accountMarginStateCache,
                notificationService, scheduleService, riskSwitchCache, triggerActivityRegistry,
                riskControlActionRepository, marketSymbolSpecRepository, fxPauseBehaviorRepository);
    }

    private TradingQuoteSnapshot stubSnapshot(Fixture f) {
        OffsetDateTime ts = OffsetDateTime.parse("2026-05-29T08:00:00Z");
        TradingQuoteSnapshot snapshot = new TradingQuoteSnapshot(
                SYMBOL,
                new BigDecimal("1.4000"),
                new BigDecimal("1.4002"),
                new BigDecimal("1.4001"),
                ts, "TM_QUOTE", false, TradingQuoteQualityStatus.FRESH, null);
        when(f.quoteRepository.save(any())).thenReturn(snapshot);
        when(f.scheduleService.isOpenAllowed(eq(SYMBOL), any())).thenReturn(true);
        when(f.riskSwitchCache.isEnabled(any(), eq(true))).thenReturn(true);
        return snapshot;
    }

    private TradingPosition openPosition(long positionId, long userId) {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-29T00:00:00Z");
        return new TradingPosition(
                positionId, 2L, userId, SYMBOL, TradingOrderSide.BUY,
                new BigDecimal("1.00000000"), new BigDecimal("1.50000000"), BigDecimal.ONE,
                new BigDecimal("0.030000"), 1, new BigDecimal("10"), new BigDecimal("1000.00000000"),
                TradingMarginMode.ISOLATED,
                new BigDecimal("1.30000000"), null, null, null, null, null,
                TradingPositionStatus.OPEN, BigDecimal.ZERO, "default",
                BigDecimal.ZERO, BigDecimal.ZERO, now, null, now);
    }

    private TradingPosition liquidatedPosition(TradingPosition open) {
        return open.close(TradingPositionStatus.LIQUIDATED, TradingPositionCloseReason.LIQUIDATION,
                new BigDecimal("1.4001"), new BigDecimal("-50.00000000"),
                OffsetDateTime.parse("2026-05-29T08:00:00Z"));
    }

    private MarketPriceTickEventPayload tick() {
        OffsetDateTime ts = OffsetDateTime.parse("2026-05-29T08:00:00Z");
        return new MarketPriceTickEventPayload(
                SYMBOL, new BigDecimal("1.4000"), new BigDecimal("1.4002"),
                new BigDecimal("1.4001"), new BigDecimal("1.4001"), ts, "TM_QUOTE", false);
    }

    @Test
    void shouldLiquidateWhenMarginLevelBreachesEvenIfLiqPriceNotHit() {
        Fixture f = newFixture();
        stubSnapshot(f);
        TradingPosition pos = openPosition(101L, 1L);
        when(f.openPositionSnapshotStore.listOpenBySymbol(SYMBOL)).thenReturn(List.of(pos));
        // liqPrice 未命中
        when(f.positionTriggerRuleEvaluator.evaluate(eq(pos), any())).thenReturn(null);
        // MarginLevel ≤ 30% → StopOut（返回触发时真实 marginLevel）
        when(f.accountMarginEvaluator.stopOutMarginLevel(eq(pos), any())).thenReturn(new BigDecimal("25.34"));
        when(f.positionCloseApplicationService.closePositionByTrigger(eq(101L), eq(TradingPositionCloseReason.LIQUIDATION), any()))
                .thenReturn(new PositionCloseResult(liquidatedPosition(pos), null, null, null));

        f.engine.processTick(tick());

        verify(f.positionCloseApplicationService, times(1))
                .closePositionByTrigger(eq(101L), eq(TradingPositionCloseReason.LIQUIDATION), any());
    }

    @Test
    void shouldStillLiquidateWhenLiqPriceHitRegression() {
        Fixture f = newFixture();
        stubSnapshot(f);
        TradingPosition pos = openPosition(102L, 2L);
        when(f.openPositionSnapshotStore.listOpenBySymbol(SYMBOL)).thenReturn(List.of(pos));
        // liqPrice 命中（既有路径）
        when(f.positionTriggerRuleEvaluator.evaluate(eq(pos), any())).thenReturn(TradingPositionCloseReason.LIQUIDATION);
        // MarginLevel 未触发
        when(f.accountMarginEvaluator.stopOutMarginLevel(eq(pos), any())).thenReturn(null);
        when(f.positionCloseApplicationService.closePositionByTrigger(eq(102L), eq(TradingPositionCloseReason.LIQUIDATION), any()))
                .thenReturn(new PositionCloseResult(liquidatedPosition(pos), null, null, null));

        f.engine.processTick(tick());

        verify(f.positionCloseApplicationService, times(1))
                .closePositionByTrigger(eq(102L), eq(TradingPositionCloseReason.LIQUIDATION), any());
    }

    @Test
    void shouldLiquidateOnlyOnceWhenBothTriggersHit() {
        Fixture f = newFixture();
        stubSnapshot(f);
        TradingPosition pos = openPosition(103L, 3L);
        when(f.openPositionSnapshotStore.listOpenBySymbol(SYMBOL)).thenReturn(List.of(pos));
        // 两条触发同时命中
        when(f.positionTriggerRuleEvaluator.evaluate(eq(pos), any())).thenReturn(TradingPositionCloseReason.LIQUIDATION);
        when(f.accountMarginEvaluator.stopOutMarginLevel(eq(pos), any())).thenReturn(new BigDecimal("18.00"));
        when(f.positionCloseApplicationService.closePositionByTrigger(eq(103L), eq(TradingPositionCloseReason.LIQUIDATION), any()))
                .thenReturn(new PositionCloseResult(liquidatedPosition(pos), null, null, null));

        f.engine.processTick(tick());

        // 同一 tick 同一仓位只强平一次
        verify(f.positionCloseApplicationService, times(1))
                .closePositionByTrigger(eq(103L), eq(TradingPositionCloseReason.LIQUIDATION), any());
    }

    @Test
    void shouldNotLiquidateOnMarginCall() {
        Fixture f = newFixture();
        stubSnapshot(f);
        TradingPosition pos = openPosition(104L, 4L);
        when(f.openPositionSnapshotStore.listOpenBySymbol(SYMBOL)).thenReturn(List.of(pos));
        when(f.positionTriggerRuleEvaluator.evaluate(eq(pos), any())).thenReturn(null);
        // MarginCall：evaluator 返回 null（告警在 evaluator/monitor 内发，不强平）
        when(f.accountMarginEvaluator.stopOutMarginLevel(eq(pos), any())).thenReturn(null);

        f.engine.processTick(tick());

        verify(f.positionCloseApplicationService, never())
                .closePositionByTrigger(any(), any(), any());
    }

    @Test
    void shouldNotLiquidateByMarginLevelWhenFxDegradedButLiqPriceStillWorks() {
        Fixture f = newFixture();
        stubSnapshot(f);
        TradingPosition pos = openPosition(105L, 5L);
        when(f.openPositionSnapshotStore.listOpenBySymbol(SYMBOL)).thenReturn(List.of(pos));
        // FX 降级：MarginLevel 不触发（返回 null）；liqPrice 命中仍强平
        when(f.accountMarginEvaluator.stopOutMarginLevel(eq(pos), any())).thenReturn(null);
        when(f.positionTriggerRuleEvaluator.evaluate(eq(pos), any())).thenReturn(TradingPositionCloseReason.LIQUIDATION);
        when(f.positionCloseApplicationService.closePositionByTrigger(eq(105L), eq(TradingPositionCloseReason.LIQUIDATION), any()))
                .thenReturn(new PositionCloseResult(liquidatedPosition(pos), null, null, null));

        f.engine.processTick(tick());

        verify(f.positionCloseApplicationService, times(1))
                .closePositionByTrigger(eq(105L), eq(TradingPositionCloseReason.LIQUIDATION), any());
    }

    @Test
    void shouldSendStopOutNotificationAndInvalidateCacheAfterStopOutLiquidation() {
        Fixture f = newFixture();
        stubSnapshot(f);
        TradingPosition pos = openPosition(106L, 6L);
        when(f.openPositionSnapshotStore.listOpenBySymbol(SYMBOL)).thenReturn(List.of(pos));
        when(f.positionTriggerRuleEvaluator.evaluate(eq(pos), any())).thenReturn(null);
        // 触发时真实 marginLevel = 25.34（百分比），通知 params 必须传该真实数值而非「≤ stopOut」字面量
        when(f.accountMarginEvaluator.stopOutMarginLevel(eq(pos), any())).thenReturn(new BigDecimal("25.34"));
        when(f.positionCloseApplicationService.closePositionByTrigger(eq(106L), eq(TradingPositionCloseReason.LIQUIDATION), any()))
                .thenReturn(new PositionCloseResult(liquidatedPosition(pos), null, null, null));

        f.engine.processTick(tick());

        // STOP_OUT_TRIGGERED 通知（V31 模板占位 ${marginLevel} / ${symbol}）
        // 断言 params 的 marginLevel 是真实数值字符串「25.34」（不再是 "≤ stopOut" 字面量占位）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(f.notificationService).send(
                eq("STOP_OUT_TRIGGERED"),
                eq(6L),
                eq("STOP_OUT_TRIGGERED"),
                paramsCaptor.capture(),
                eq("RISK"),
                eq(106L),
                any());
        Map<String, String> params = paramsCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("25.34", params.get("marginLevel"));
        org.junit.jupiter.api.Assertions.assertNotEquals("≤ stopOut", params.get("marginLevel"));
        // marginLevel 必须是合法数字格式（不含阈值文案占位符）
        org.junit.jupiter.api.Assertions.assertTrue(params.get("marginLevel").matches("\\d+\\.\\d{2}"));
        org.junit.jupiter.api.Assertions.assertEquals(SYMBOL, params.get("symbol"));
        // 账户余额已变 → 失效该用户缓存
        verify(f.accountMarginStateCache).invalidate(6L);
    }

    @Test
    void shouldNotSendStopOutNotificationWhenLiquidationDrivenByLiqPriceOnly() {
        Fixture f = newFixture();
        stubSnapshot(f);
        TradingPosition pos = openPosition(107L, 7L);
        when(f.openPositionSnapshotStore.listOpenBySymbol(SYMBOL)).thenReturn(List.of(pos));
        // 仅 liqPrice 触发，MarginLevel 未触发 → 不发 STOP_OUT_TRIGGERED（POSITION_LIQUIDATED 由 close 服务发）
        when(f.positionTriggerRuleEvaluator.evaluate(eq(pos), any())).thenReturn(TradingPositionCloseReason.LIQUIDATION);
        when(f.accountMarginEvaluator.stopOutMarginLevel(eq(pos), any())).thenReturn(null);
        when(f.positionCloseApplicationService.closePositionByTrigger(eq(107L), eq(TradingPositionCloseReason.LIQUIDATION), any()))
                .thenReturn(new PositionCloseResult(liquidatedPosition(pos), null, null, null));

        f.engine.processTick(tick());

        verify(f.notificationService, never()).send(eq("STOP_OUT_TRIGGERED"), any(Long.class), any(), any(), any(), any(), any());
    }

    // ─── STAGE-14C2 Task 6：FX_PAUSED 按类目控制被动强平（allow_liquidation）────────

    /** SYMBOL=EURAUD（forex/cat2）。 */
    private SymbolSpec forexSpec() {
        return new SymbolSpec(SYMBOL, 300, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.00000001"), new BigDecimal("1000000000"), BigDecimal.ZERO,
                8, 8, "EUR", "AUD", 2);
    }

    /**
     * Task 6：GLOBAL_PAUSE 激活 + forex(cat2) allow_liquidation=false → 跳过被动强平（不调 close）。
     */
    @Test
    void shouldSkipLiquidationWhenPauseActiveAndCategoryDisallowsLiquidation() {
        Fixture f = newFixture();
        stubSnapshot(f);
        TradingPosition pos = openPosition(201L, 1L);
        when(f.openPositionSnapshotStore.listOpenBySymbol(SYMBOL)).thenReturn(List.of(pos));
        // liqPrice 命中 → 本应强平
        when(f.positionTriggerRuleEvaluator.evaluate(eq(pos), any())).thenReturn(TradingPositionCloseReason.LIQUIDATION);
        when(f.accountMarginEvaluator.stopOutMarginLevel(eq(pos), any())).thenReturn(null);
        // pause 激活 + forex allow_liquidation=false
        when(f.riskControlActionRepository.hasActiveGlobalPause()).thenReturn(true);
        when(f.marketSymbolSpecRepository.findByPlatformSymbol(SYMBOL)).thenReturn(Optional.of(forexSpec()));
        when(f.fxPauseBehaviorRepository.findByCategory(2))
                .thenReturn(Optional.of(new FxPauseBehavior(2, "forex", false, true, false)));

        f.engine.processTick(tick());

        verify(f.positionCloseApplicationService, never()).closePositionByTrigger(any(), any(), any());
    }

    /**
     * Task 6：GLOBAL_PAUSE 激活 + crypto(cat1) allow_liquidation=true → 强平继续（正常 close）。
     */
    @Test
    void shouldStillLiquidateWhenPauseActiveButCategoryAllowsLiquidation() {
        Fixture f = newFixture();
        stubSnapshot(f);
        TradingPosition pos = openPosition(202L, 2L);
        when(f.openPositionSnapshotStore.listOpenBySymbol(SYMBOL)).thenReturn(List.of(pos));
        when(f.positionTriggerRuleEvaluator.evaluate(eq(pos), any())).thenReturn(TradingPositionCloseReason.LIQUIDATION);
        when(f.accountMarginEvaluator.stopOutMarginLevel(eq(pos), any())).thenReturn(null);
        when(f.riskControlActionRepository.hasActiveGlobalPause()).thenReturn(true);
        // SYMBOL 仍是 EURAUD 但本用例把它当 crypto(cat1) allow_liquidation=true 验证放行分支
        when(f.marketSymbolSpecRepository.findByPlatformSymbol(SYMBOL))
                .thenReturn(Optional.of(new SymbolSpec(SYMBOL, 300, BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("0.00000001"), new BigDecimal("1000000000"), BigDecimal.ZERO,
                        8, 8, "BTC", "USDT", 1)));
        when(f.fxPauseBehaviorRepository.findByCategory(1))
                .thenReturn(Optional.of(new FxPauseBehavior(1, "crypto", true, true, true)));
        when(f.positionCloseApplicationService.closePositionByTrigger(eq(202L), eq(TradingPositionCloseReason.LIQUIDATION), any()))
                .thenReturn(new PositionCloseResult(liquidatedPosition(pos), null, null, null));

        f.engine.processTick(tick());

        verify(f.positionCloseApplicationService, times(1))
                .closePositionByTrigger(eq(202L), eq(TradingPositionCloseReason.LIQUIDATION), any());
    }

    /**
     * Task 6 降级（与开仓相反方向）：pause 激活 + category==null（过渡期旧快照）→ 强平继续（不因缺信息阻止）。
     */
    @Test
    void shouldStillLiquidateWhenPauseActiveButCategoryNull() {
        Fixture f = newFixture();
        stubSnapshot(f);
        TradingPosition pos = openPosition(203L, 3L);
        when(f.openPositionSnapshotStore.listOpenBySymbol(SYMBOL)).thenReturn(List.of(pos));
        when(f.positionTriggerRuleEvaluator.evaluate(eq(pos), any())).thenReturn(TradingPositionCloseReason.LIQUIDATION);
        when(f.accountMarginEvaluator.stopOutMarginLevel(eq(pos), any())).thenReturn(null);
        when(f.riskControlActionRepository.hasActiveGlobalPause()).thenReturn(true);
        // 过渡期旧快照：spec.category()==null
        when(f.marketSymbolSpecRepository.findByPlatformSymbol(SYMBOL))
                .thenReturn(Optional.of(new SymbolSpec(SYMBOL, 300, BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("0.00000001"), new BigDecimal("1000000000"), BigDecimal.ZERO,
                        8, 8, "EUR", "AUD", null)));
        when(f.positionCloseApplicationService.closePositionByTrigger(eq(203L), eq(TradingPositionCloseReason.LIQUIDATION), any()))
                .thenReturn(new PositionCloseResult(liquidatedPosition(pos), null, null, null));

        f.engine.processTick(tick());

        // 降级继续强平：不查 behavior
        verify(f.positionCloseApplicationService, times(1))
                .closePositionByTrigger(eq(203L), eq(TradingPositionCloseReason.LIQUIDATION), any());
        verify(f.fxPauseBehaviorRepository, never()).findByCategory(org.mockito.ArgumentMatchers.anyInt());
    }
}
