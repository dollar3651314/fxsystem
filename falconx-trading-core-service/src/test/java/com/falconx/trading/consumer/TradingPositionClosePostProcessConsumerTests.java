package com.falconx.trading.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.falconx.trading.entity.TradingLiquidationLog;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.repository.TradingLiquidationLogRepository;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.TradingTradeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Sprint 3 S6 Task 5：post-process 消费者单测。
 */
class TradingPositionClosePostProcessConsumerTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TRADE_WRITE_PAYLOAD = """
            {
              "eventType": "TRADE_WRITE",
              "tradeId": 5001,
              "orderId": 100,
              "positionId": 200,
              "userId": 7,
              "symbol": "EURUSD",
              "side": "BUY",
              "tradeType": "CLOSE",
              "quantity": "0.01",
              "price": "1.10000000",
              "fee": "0.00000000",
              "realizedPnl": "0.50000000",
              "tradedAt": "2026-05-26T10:00:00Z"
            }
            """;

    private static final String LIQUIDATION_LOG_PAYLOAD = """
            {
              "eventType": "LIQUIDATION_LOG_WRITE",
              "userId": 7,
              "positionId": 200,
              "symbol": "EURUSD",
              "side": "BUY",
              "marginMode": "ISOLATED",
              "quantity": "0.01",
              "entryPrice": "1.10000000",
              "liquidationPrice": "1.09000000",
              "closePrice": "1.09100000",
              "quoteTs": "2026-05-26T10:00:00Z",
              "quoteSource": "test",
              "netLossAfterMargin": "10.00000000",
              "liquidationFee": "0.00000000",
              "marginReleased": "100.00000000",
              "platformCoveredLoss": "0.00000000",
              "occurredAt": "2026-05-26T10:00:00Z"
            }
            """;

    private static final String CASCADE_PAYLOAD = """
            {
              "eventType": "PENDING_ORDER_CASCADE_CANCEL",
              "positionId": 200,
              "closeReason": "MANUAL"
            }
            """;

    @Test
    void handleTradeWrite_callsSaveWithExplicitIdUsingPayloadTradeId() {
        TradingTradeRepository tradeRepo = mock(TradingTradeRepository.class);
        TradingLiquidationLogRepository liqRepo = mock(TradingLiquidationLogRepository.class);
        TradingPendingOrderTriggerRepository pendingRepo = mock(TradingPendingOrderTriggerRepository.class);

        TradingPositionClosePostProcessConsumer consumer = new TradingPositionClosePostProcessConsumer(
                MAPPER, tradeRepo, liqRepo, pendingRepo);

        consumer.consume(TRADE_WRITE_PAYLOAD, "event-1");

        ArgumentCaptor<TradingTrade> tradeCaptor = ArgumentCaptor.forClass(TradingTrade.class);
        verify(tradeRepo).saveWithExplicitId(tradeCaptor.capture());
        assertEquals(5001L, tradeCaptor.getValue().tradeId());
        assertEquals("EURUSD", tradeCaptor.getValue().symbol());
        verify(liqRepo, never()).save(any());
        verify(pendingRepo, never()).cancelAllSlTpByPositionId(any(), any());
    }

    @Test
    void handleLiquidationLogWrite_callsSaveOnRepo() {
        TradingTradeRepository tradeRepo = mock(TradingTradeRepository.class);
        TradingLiquidationLogRepository liqRepo = mock(TradingLiquidationLogRepository.class);
        TradingPendingOrderTriggerRepository pendingRepo = mock(TradingPendingOrderTriggerRepository.class);

        TradingPositionClosePostProcessConsumer consumer = new TradingPositionClosePostProcessConsumer(
                MAPPER, tradeRepo, liqRepo, pendingRepo);

        consumer.consume(LIQUIDATION_LOG_PAYLOAD, "event-2");

        ArgumentCaptor<TradingLiquidationLog> captor = ArgumentCaptor.forClass(TradingLiquidationLog.class);
        verify(liqRepo).save(captor.capture());
        assertNotNull(captor.getValue());
        assertEquals(200L, captor.getValue().positionId());
        verify(tradeRepo, never()).saveWithExplicitId(any());
        verify(pendingRepo, never()).cancelAllSlTpByPositionId(any(), any());
    }

    @Test
    void handlePendingOrderCascadeCancel_callsRepoWithFormattedReason() {
        TradingTradeRepository tradeRepo = mock(TradingTradeRepository.class);
        TradingLiquidationLogRepository liqRepo = mock(TradingLiquidationLogRepository.class);
        TradingPendingOrderTriggerRepository pendingRepo = mock(TradingPendingOrderTriggerRepository.class);

        TradingPositionClosePostProcessConsumer consumer = new TradingPositionClosePostProcessConsumer(
                MAPPER, tradeRepo, liqRepo, pendingRepo);

        consumer.consume(CASCADE_PAYLOAD, "event-3");

        verify(pendingRepo).cancelAllSlTpByPositionId(eq(200L), eq("PARENT_POSITION_CLOSED_BY_MANUAL"));
        verify(tradeRepo, never()).saveWithExplicitId(any());
        verify(liqRepo, never()).save(any());
    }

    @Test
    void handlePendingOrderCascadeCancel_nullRepo_skipsGracefully() {
        TradingTradeRepository tradeRepo = mock(TradingTradeRepository.class);
        TradingLiquidationLogRepository liqRepo = mock(TradingLiquidationLogRepository.class);

        TradingPositionClosePostProcessConsumer consumer = new TradingPositionClosePostProcessConsumer(
                MAPPER, tradeRepo, liqRepo, null);

        // 不抛异常即通过
        consumer.consume(CASCADE_PAYLOAD, "event-4");
    }

    @Test
    void unknownEventType_logsWarnAndDoesNotThrow() {
        TradingTradeRepository tradeRepo = mock(TradingTradeRepository.class);
        TradingLiquidationLogRepository liqRepo = mock(TradingLiquidationLogRepository.class);
        TradingPendingOrderTriggerRepository pendingRepo = mock(TradingPendingOrderTriggerRepository.class);

        TradingPositionClosePostProcessConsumer consumer = new TradingPositionClosePostProcessConsumer(
                MAPPER, tradeRepo, liqRepo, pendingRepo);

        String unknown = """
                { "eventType": "UNKNOWN_FOO" }
                """;
        // 不抛异常
        consumer.consume(unknown, "event-5");
        verify(tradeRepo, never()).saveWithExplicitId(any());
        verify(liqRepo, never()).save(any());
        verify(pendingRepo, never()).cancelAllSlTpByPositionId(any(), any());
    }
}
