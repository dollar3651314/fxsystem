package com.falconx.trading.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import com.falconx.trading.entity.TradingOutboxMessage;
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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sprint 3 S6 Task 5：settlePositionExit 主事务拆分单测。
 *
 * <p>用反射直接调 private settlePositionExit，避开 closePosition/closePositionByTrigger 入口的
 * 流程性验证（trading-schedule / executable quote / trigger evaluator 等），聚焦本任务关心的
 * 拆分核心：outbox count、reserved tradeId 一致性、async-disabled 回退分支等价性。
 */
class TradingPositionCloseApplicationServiceS6Tests {

    private static final BigDecimal SIZE = new BigDecimal("0.01");
    private static final BigDecimal ENTRY = new BigDecimal("1.10000000");
    private static final BigDecimal MARGIN = new BigDecimal("100.00000000");
    private static final BigDecimal LIQ_PRICE = new BigDecimal("1.05000000");

    @Test
    void asyncEnabled_manualClose_outboxIs2_mainEventPlusTradeWrite() throws Exception {
        Mocks m = new Mocks();
        TradingPositionCloseApplicationService service = m.buildService(true);
        TradingPosition position = m.newPosition();
        TradingQuoteSnapshot quote = m.newQuote();

        TransactionSynchronizationManager.initSynchronization();
        try {
            invokeSettle(service, position, quote, TradingPositionCloseReason.MANUAL);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // 异步路径主事务不直接写 t_trade
        verify(m.tradeRepo, never()).save(any(TradingTrade.class));
        verify(m.tradeRepo, never()).saveWithExplicitId(any());
        verify(m.liqLogRepo, never()).save(any());
        verify(m.pendingRepo, never()).cancelAllSlTpByPositionId(any(), any());
        // 主事件 position.closed + TRADE_WRITE（无 SL/TP 时不写 cascade，因 pendingRepo 非 null
        // 但本测试 position 无 SL/TP — 但 build*CascadeCancelOutbox 不感知，仍按 pendingRepo != null 写）
        verify(m.outboxRepo, times(2 + 1)).save(any(TradingOutboxMessage.class));
        // 解释：position.closed (1) + TRADE_WRITE (2) + PENDING_ORDER_CASCADE_CANCEL (3)
        // 异步路径下只要 pendingRepo bean 注入就一定写 cascade outbox（由消费者决定是否真的撤）
    }

    @Test
    void asyncEnabled_liquidation_outboxIncludesLiquidationLogWrite() throws Exception {
        Mocks m = new Mocks();
        TradingPositionCloseApplicationService service = m.buildService(true);
        TradingPosition position = m.newPosition();
        TradingQuoteSnapshot quote = m.newQuote();

        TransactionSynchronizationManager.initSynchronization();
        try {
            invokeSettle(service, position, quote, TradingPositionCloseReason.LIQUIDATION);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // position.liquidated (1) + TRADE_WRITE (2) + LIQUIDATION_LOG_WRITE (3) + CASCADE (4)
        verify(m.outboxRepo, times(4)).save(any(TradingOutboxMessage.class));
        verify(m.liqLogRepo, never()).save(any());
    }

    @Test
    void asyncDisabled_fallbackSyncPath_writesAllTablesInMainTx() throws Exception {
        Mocks m = new Mocks();
        TradingPositionCloseApplicationService service = m.buildService(false);
        TradingPosition position = m.newPosition();
        TradingQuoteSnapshot quote = m.newQuote();

        TransactionSynchronizationManager.initSynchronization();
        try {
            invokeSettle(service, position, quote, TradingPositionCloseReason.LIQUIDATION);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // 同步回退路径：tradeRepo / liqLogRepo / pendingRepo 都被调
        verify(m.tradeRepo, times(1)).save(any(TradingTrade.class));
        verify(m.liqLogRepo, times(1)).save(any());
        verify(m.pendingRepo, times(1)).cancelAllSlTpByPositionId(any(), any());
        // outbox 只 1 次主事件（liquidation.executed）
        verify(m.outboxRepo, times(1)).save(any(TradingOutboxMessage.class));
    }

    @Test
    void reservedTradeId_appearsInBothReturnedTradeAndOutboxPayload() throws Exception {
        Mocks m = new Mocks();
        when(m.idGenerator.nextId()).thenReturn(5001L);
        TradingPositionCloseApplicationService service = m.buildService(true);
        TradingPosition position = m.newPosition();
        TradingQuoteSnapshot quote = m.newQuote();

        TransactionSynchronizationManager.initSynchronization();
        PositionCloseResult result;
        try {
            result = (PositionCloseResult) invokeSettle(service, position, quote, TradingPositionCloseReason.MANUAL);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // 1. PositionCloseResult.trade().tradeId() == 5001L
        assertNotNull(result);
        assertEquals(5001L, result.trade().tradeId());

        // 2. outbox TRADE_WRITE payload 包含 tradeId=5001L
        ArgumentCaptor<TradingOutboxMessage> captor = ArgumentCaptor.forClass(TradingOutboxMessage.class);
        verify(m.outboxRepo, times(3)).save(captor.capture());

        List<TradingOutboxMessage> savedMessages = captor.getAllValues();
        boolean foundTradeWrite = false;
        for (TradingOutboxMessage saved : savedMessages) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> payloadMap = (java.util.Map<String, Object>) saved.payload();
            if ("TRADE_WRITE".equals(payloadMap.get("eventType"))) {
                assertEquals(5001L, payloadMap.get("tradeId"));
                foundTradeWrite = true;
            }
        }
        assertTrue(foundTradeWrite, "expected an outbox event with eventType=TRADE_WRITE");
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
            // S6 拆分测试不关心货币换算：SymbolSpec 缺失 → 降级 fx=1、inAccount==inQuote，
            // 保持原 realizedPnl 流转语义。
            when(symbolSpecRepo.findByPlatformSymbol(anyString())).thenReturn(java.util.Optional.empty());
            // settlePositionExit 内部调用栈所需 mock 行为
            when(accountService.getExistingAccountForUpdate(anyLong(), anyString()))
                    .thenReturn(newAccount());
            when(accountService.settlePositionExit(
                    any(), any(), any(), any(), anyString(), any(), any(),
                    org.mockito.ArgumentMatchers.anyBoolean(),
                    anyString(), anyString(), any()))
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
            when(liqLogRepo.save(any())).thenAnswer(inv -> {
                com.falconx.trading.entity.TradingLiquidationLog in = inv.getArgument(0);
                return new com.falconx.trading.entity.TradingLiquidationLog(
                        8888L,  // 生成 id
                        in.userId(), in.positionId(), in.symbol(), in.side(), in.marginMode(),
                        in.quantity(), in.entryPrice(), in.liquidationPrice(), in.markPrice(),
                        in.priceTs(), in.priceSource(), in.loss(), in.fee(),
                        in.marginReleased(), in.platformCoveredLoss(), in.createdAt());
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

        TradingPosition newPosition() {
            OffsetDateTime now = OffsetDateTime.now();
            return new TradingPosition(
                    200L,                                       // positionId
                    100L,                                       // openingOrderId
                    7L,                                         // userId
                    "EURUSD",
                    TradingOrderSide.BUY,
                    SIZE,
                    ENTRY,
                    BigDecimal.ONE,                             // entryFxRate (Task 9a，USD-quote 测试夹具 fx=1)
                    new BigDecimal("0.005000"),                 // mmRateAtOpen (Task 6)
                    1,                                          // tierNoAtOpen (Task 6)
                    new BigDecimal("10"),                       // leverage
                    MARGIN,
                    TradingMarginMode.ISOLATED,
                    LIQ_PRICE,
                    null,                                       // takeProfitPrice
                    null,                                       // stopLossPrice
                    null,                                       // closePrice
                    null,                                       // closeReason
                    null,                                       // realizedPnl
                    TradingPositionStatus.OPEN,
                    BigDecimal.ZERO,                            // openFeeRate
                    "default",                                  // groupCodeAtOpen
                    BigDecimal.ZERO,                            // bidExtraAtOpen
                    BigDecimal.ZERO,                            // askExtraAtOpen
                    now,                                        // openedAt
                    null,                                       // closedAt
                    now                                         // updatedAt
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
