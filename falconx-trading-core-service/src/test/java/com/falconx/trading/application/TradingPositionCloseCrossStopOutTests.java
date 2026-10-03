package com.falconx.trading.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.dto.PositionCloseResult;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.PositionTriggerRuleEvaluator;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionCloseReason;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.entity.TradingQuoteQualityStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.repository.TradingLiquidationLogRepository;
import com.falconx.trading.repository.TradingOutboxRepository;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.repository.TradingTradeRepository;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.trading.service.TradingAccountService.PositionSettlementResult;
import com.falconx.trading.service.TradingRiskObservabilityService;
import com.falconx.trading.service.TradingScheduleService;
import com.falconx.trading.websocket.TradingUserRealtimePushService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * STAGE-14D2 Task 1：CROSS_STOP_OUT close_reason + 放开 closePositionByTrigger 二次价格校验
 * + settlePositionExit liquidation 判断扩。
 *
 * <p>核心断言：
 * <ul>
 *   <li>CROSS_STOP_OUT（账户级触发，liquidationPrice=null）走 closePositionByTrigger
 *       <b>跳过</b> PositionTriggerRuleEvaluator 二次价格校验 → 成功平仓（不再被静默吞）。</li>
 *   <li>CROSS_STOP_OUT 经 settlePositionExit → LIQUIDATED 状态 + LIQUIDATION trade type
 *       + biz_type=LIQUIDATION_PNL（biz_type=9）落账。</li>
 *   <li>LIQUIDATION（ISOLATED liqPrice）二次校验保持：未命中 → evaluate=null → 静默 return（回归）。</li>
 *   <li>STOP_LOSS 二次校验保持（回归）。</li>
 * </ul>
 */
class TradingPositionCloseCrossStopOutTests {

    private static final BigDecimal SIZE = new BigDecimal("0.01");
    private static final BigDecimal ENTRY = new BigDecimal("1.10000000");
    private static final BigDecimal MARGIN = new BigDecimal("100.00000000");
    private static final BigDecimal ISO_LIQ_PRICE = new BigDecimal("1.05000000");

    /**
     * CROSS 仓 liquidationPrice=null：closePositionByTrigger(CROSS_STOP_OUT) 应跳过二次校验、成功平仓。
     */
    @Test
    void crossStopOut_skipsRevalidation_andLiquidatesNullLiqPricePosition() {
        Mocks m = new Mocks();
        TradingPositionCloseApplicationService service = m.buildService(true);
        TradingPosition crossPos = m.newCrossPositionLiqPriceNull();
        when(m.positionRepo.findByIdForUpdate(anyLong())).thenReturn(Optional.of(crossPos));
        TradingQuoteSnapshot quote = m.newQuote();

        TransactionSynchronizationManager.initSynchronization();
        PositionCloseResult result;
        try {
            result = service.closePositionByTrigger(crossPos.positionId(),
                    TradingPositionCloseReason.CROSS_STOP_OUT, quote);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertNotNull(result, "CROSS_STOP_OUT 应成功平仓，不被静默吞");
        // 关键：二次价格校验 evaluate 完全没被调用（绕过价格门控）
        verify(m.triggerEval, never()).evaluate(any(), any());
        // 走 LIQUIDATED 状态 + LIQUIDATION trade type
        assertEquals(TradingPositionStatus.LIQUIDATED, result.position().status());
        assertEquals(TradingPositionCloseReason.CROSS_STOP_OUT, result.position().closeReason());
        assertEquals(com.falconx.trading.entity.TradingTradeType.LIQUIDATION, result.trade().tradeType());
        // biz_type=9（LIQUIDATION_PNL）+ liquidation=true 落账
        verify(m.accountService, times(1)).settlePositionExit(
                any(), any(), any(), any(), anyString(), any(),
                org.mockito.ArgumentMatchers.eq(com.falconx.trading.entity.TradingLedgerBizType.LIQUIDATION_PNL),
                org.mockito.ArgumentMatchers.eq(true),
                anyString(), anyString(), any());
    }

    /**
     * 回归：ISOLATED LIQUIDATION 二次校验保持——未命中 liqPrice → evaluate 返回 null → 静默 return。
     */
    @Test
    void isolatedLiquidation_revalidationStillApplies_returnsNullWhenNotHit() {
        Mocks m = new Mocks();
        TradingPositionCloseApplicationService service = m.buildService(true);
        TradingPosition isoPos = m.newIsolatedPosition();
        when(m.positionRepo.findByIdForUpdate(anyLong())).thenReturn(Optional.of(isoPos));
        // 价格未触及 liqPrice → evaluate 返回 null（默认 mock 返回 null）
        TradingQuoteSnapshot quote = m.newQuote();

        TransactionSynchronizationManager.initSynchronization();
        PositionCloseResult result;
        try {
            result = service.closePositionByTrigger(isoPos.positionId(),
                    TradingPositionCloseReason.LIQUIDATION, quote);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertNull(result, "LIQUIDATION 二次校验未命中应静默 return null（回归）");
        verify(m.triggerEval, times(1)).evaluate(any(), any());
        verify(m.accountService, never()).settlePositionExit(
                any(), any(), any(), any(), anyString(), any(), any(), anyBoolean(),
                anyString(), anyString(), any());
    }

    /**
     * 回归：STOP_LOSS 二次校验保持——命中则正常平仓（CLOSED 状态）。
     */
    @Test
    void isolatedStopLoss_revalidationStillApplies_closesWhenHit() {
        Mocks m = new Mocks();
        TradingPositionCloseApplicationService service = m.buildService(true);
        TradingPosition isoPos = m.newIsolatedPosition();
        when(m.positionRepo.findByIdForUpdate(anyLong())).thenReturn(Optional.of(isoPos));
        when(m.triggerEval.evaluate(any(), any())).thenReturn(TradingPositionCloseReason.STOP_LOSS);
        TradingQuoteSnapshot quote = m.newQuote();

        TransactionSynchronizationManager.initSynchronization();
        PositionCloseResult result;
        try {
            result = service.closePositionByTrigger(isoPos.positionId(),
                    TradingPositionCloseReason.STOP_LOSS, quote);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertNotNull(result);
        verify(m.triggerEval, times(1)).evaluate(any(), any());
        // STOP_LOSS 非强平 → CLOSED 状态 + CLOSE trade type
        assertEquals(TradingPositionStatus.CLOSED, result.position().status());
        assertEquals(com.falconx.trading.entity.TradingTradeType.CLOSE, result.trade().tradeType());
    }

    /**
     * settlePositionExit：CROSS_STOP_OUT 与 LIQUIDATION 同口径 → LIQUIDATED + LIQUIDATION trade type。
     */
    @Test
    void settlePositionExit_crossStopOut_treatedAsLiquidation() throws Exception {
        Mocks m = new Mocks();
        TradingPositionCloseApplicationService service = m.buildService(true);
        TradingPosition crossPos = m.newCrossPositionLiqPriceNull();
        TradingQuoteSnapshot quote = m.newQuote();

        TransactionSynchronizationManager.initSynchronization();
        PositionCloseResult result;
        try {
            result = (PositionCloseResult) invokeSettle(service, crossPos, quote,
                    TradingPositionCloseReason.CROSS_STOP_OUT);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertNotNull(result);
        assertEquals(TradingPositionStatus.LIQUIDATED, result.position().status());
        assertEquals(com.falconx.trading.entity.TradingTradeType.LIQUIDATION, result.trade().tradeType());
    }

    // ===== invocation helper =====
    private Object invokeSettle(TradingPositionCloseApplicationService service,
                                TradingPosition position,
                                TradingQuoteSnapshot quote,
                                TradingPositionCloseReason reason) throws Exception {
        Method m = TradingPositionCloseApplicationService.class.getDeclaredMethod(
                "settlePositionExit",
                TradingPosition.class, TradingQuoteSnapshot.class,
                TradingPositionCloseReason.class, OffsetDateTime.class);
        m.setAccessible(true);
        return m.invoke(service, position, quote, reason, OffsetDateTime.now());
    }

    // ===== mock fixture =====
    private static final class Mocks {
        final TradingCoreServiceProperties properties = new TradingCoreServiceProperties();
        final TradingPositionRepository positionRepo = mock(TradingPositionRepository.class);
        final TradingTradeRepository tradeRepo = mock(TradingTradeRepository.class);
        final TradingQuoteSnapshotRepository quoteRepo = mock(TradingQuoteSnapshotRepository.class);
        final TradingAccountService accountService = mock(TradingAccountService.class);
        final TradingRiskObservabilityService riskObsService = mock(TradingRiskObservabilityService.class);
        final TradingOutboxRepository outboxRepo = mock(TradingOutboxRepository.class);
        final TradingLiquidationLogRepository liqLogRepo = mock(TradingLiquidationLogRepository.class);
        final TradingScheduleService scheduleService = mock(TradingScheduleService.class);
        final OpenPositionSnapshotStore snapshotStore = mock(OpenPositionSnapshotStore.class);
        final PositionTriggerRuleEvaluator triggerEval = mock(PositionTriggerRuleEvaluator.class);
        final TradingUserRealtimePushService realtimeService = mock(TradingUserRealtimePushService.class);
        final TradingPendingOrderTriggerRepository pendingRepo = mock(TradingPendingOrderTriggerRepository.class);
        final IdGenerator idGenerator = mock(IdGenerator.class);
        final com.falconx.trading.repository.MarketSymbolSpecRepository symbolSpecRepo =
                mock(com.falconx.trading.repository.MarketSymbolSpecRepository.class);
        final com.falconx.trading.service.FxRateService fxRateService =
                mock(com.falconx.trading.service.FxRateService.class);

        Mocks() {
            when(symbolSpecRepo.findByPlatformSymbol(anyString())).thenReturn(Optional.empty());
            when(scheduleService.isOpenAllowed(anyString(), any())).thenReturn(true);
            when(scheduleService.isCloseAllowed(anyString(), any())).thenReturn(true);
            when(accountService.getExistingAccountForUpdate(anyLong(), anyString()))
                    .thenReturn(newAccount());
            when(accountService.settlePositionExit(
                    any(), any(), any(), any(), anyString(), any(), any(),
                    anyBoolean(), anyString(), anyString(), any()))
                    .thenAnswer(inv -> new PositionSettlementResult(
                            newAccount(),
                            BigDecimal.ZERO.setScale(8),
                            BigDecimal.ZERO.setScale(8)));
            when(positionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(tradeRepo.save(any())).thenAnswer(inv -> {
                TradingTrade in = inv.getArgument(0);
                return new TradingTrade(9999L, in.orderId(), in.positionId(), in.userId(),
                        in.symbol(), in.side(), in.tradeType(), in.quantity(), in.price(),
                        in.fee(), in.realizedPnl(), in.tradedAt());
            });
            when(idGenerator.nextId()).thenReturn(7777L);
        }

        TradingPositionCloseApplicationService buildService(boolean asyncEnabled) {
            return new TradingPositionCloseApplicationService(
                    properties, positionRepo, tradeRepo, quoteRepo, accountService,
                    riskObsService, outboxRepo, liqLogRepo, scheduleService,
                    snapshotStore, triggerEval, realtimeService,
                    pendingRepo, null, idGenerator, asyncEnabled,
                    symbolSpecRepo, fxRateService);
        }

        TradingPosition newCrossPositionLiqPriceNull() {
            return newPosition(TradingMarginMode.CROSS, null);
        }

        TradingPosition newIsolatedPosition() {
            return newPosition(TradingMarginMode.ISOLATED, ISO_LIQ_PRICE);
        }

        private TradingPosition newPosition(TradingMarginMode mode, BigDecimal liqPrice) {
            OffsetDateTime now = OffsetDateTime.now();
            return new TradingPosition(
                    200L, 100L, 7L,
                    "EURUSD",
                    TradingOrderSide.BUY,
                    SIZE,
                    ENTRY,
                    BigDecimal.ONE,
                    new BigDecimal("0.005000"),
                    1,
                    new BigDecimal("10"),
                    MARGIN,
                    mode,
                    liqPrice,
                    null,
                    null,
                    null,
                    null,
                    null,
                    TradingPositionStatus.OPEN,
                    BigDecimal.ZERO,
                    "default",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    now,
                    null,
                    now
            );
        }

        TradingQuoteSnapshot newQuote() {
            return new TradingQuoteSnapshot(
                    "EURUSD",
                    new BigDecimal("1.10500000"),
                    new BigDecimal("1.10510000"),
                    new BigDecimal("1.10505000"),
                    OffsetDateTime.now(),
                    "TEST",
                    false,
                    TradingQuoteQualityStatus.FRESH,
                    null
            );
        }

        TradingAccount newAccount() {
            OffsetDateTime now = OffsetDateTime.now();
            return new TradingAccount(
                    100L, 7L, "USDT",
                    new BigDecimal("500.00000000"),
                    BigDecimal.ZERO.setScale(8),
                    BigDecimal.ZERO.setScale(8),
                    TradingMarginMode.ISOLATED,
                    null, null,
                    now, now);
        }
    }
}
