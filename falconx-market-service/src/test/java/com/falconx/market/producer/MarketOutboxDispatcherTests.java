package com.falconx.market.producer;

import com.falconx.market.entity.MarketOutboxMessage;
import com.falconx.market.entity.MarketOutboxStatus;
import com.falconx.market.repository.MarketOutboxRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * market-service Outbox 调度器测试。
 *
 * <p>该测试验证低频市场事件已经切换到
 * “认领批次 -> Kafka 发送 -> 成功回写 / 失败退避”的调度语义。
 */
class MarketOutboxDispatcherTests {

    @Test
    void shouldDispatchPendingMarketOutboxMessageAndMarkItAsSent() {
        MarketOutboxRepository repository = mock(MarketOutboxRepository.class);
        MarketOutboxMessage pendingMessage = new MarketOutboxMessage(
                "outbox-2001",
                "evt-market-2001",
                "market.kline.update",
                "BTCUSDT:1m",
                "payload",
                MarketOutboxStatus.PENDING,
                OffsetDateTime.now(),
                null,
                0,
                OffsetDateTime.now(),
                null
        );
        when(repository.claimDispatchableBatch(any(OffsetDateTime.class), eq(200)))
                .thenReturn(List.of(pendingMessage));

        MarketOutboxDispatcher dispatcher = new MarketOutboxDispatcher(repository, message -> {
        });

        int dispatchedCount = dispatcher.dispatchPendingMessages();

        Assertions.assertEquals(1, dispatchedCount);
        verify(repository).markSent(eq("outbox-2001"), any(OffsetDateTime.class));
        verify(repository, never()).markFailed(any(), any(), any(), any(Integer.class));
    }

    @Test
    void shouldMarkMarketOutboxMessageAsFailedWhenPublishThrows() {
        MarketOutboxRepository repository = mock(MarketOutboxRepository.class);
        MarketOutboxMessage pendingMessage = new MarketOutboxMessage(
                "outbox-2002",
                "evt-market-2002",
                "market.kline.update",
                "EURUSD:1m",
                "payload",
                MarketOutboxStatus.PENDING,
                OffsetDateTime.now(),
                null,
                0,
                OffsetDateTime.now(),
                null
        );
        when(repository.claimDispatchableBatch(any(OffsetDateTime.class), eq(200)))
                .thenReturn(List.of(pendingMessage));
        MarketOutboxEventPublisher publisher = mock(MarketOutboxEventPublisher.class);
        doThrow(new IllegalStateException("mock publish failure")).when(publisher).publish(any(MarketOutboxMessage.class));

        MarketOutboxDispatcher dispatcher = new MarketOutboxDispatcher(repository, publisher);
        int dispatchedCount = dispatcher.dispatchPendingMessages();

        ArgumentCaptor<OffsetDateTime> nextRetryCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        Assertions.assertEquals(0, dispatchedCount);
        verify(repository).markFailed(eq("outbox-2002"), nextRetryCaptor.capture(), eq("mock publish failure"), eq(10));
        Assertions.assertNotNull(nextRetryCaptor.getValue());
        verify(repository, never()).markSent(any(), any());
    }

    @Test
    void shouldApplyJitterAndThirtyMinuteCeilingForHigherRetryCount() {
        MarketOutboxRepository repository = mock(MarketOutboxRepository.class);
        MarketOutboxMessage pendingMessage = new MarketOutboxMessage(
                "outbox-2003",
                "evt-market-2003",
                "market.kline.update",
                "XAUUSD:1m",
                "payload",
                MarketOutboxStatus.FAILED,
                OffsetDateTime.now(),
                null,
                3,
                OffsetDateTime.now(),
                "previous failure"
        );
        when(repository.claimDispatchableBatch(any(OffsetDateTime.class), eq(200)))
                .thenReturn(List.of(pendingMessage));
        MarketOutboxEventPublisher publisher = mock(MarketOutboxEventPublisher.class);
        doThrow(new IllegalStateException("still failing")).when(publisher).publish(any(MarketOutboxMessage.class));

        OffsetDateTime beforeDispatch = OffsetDateTime.now();
        MarketOutboxDispatcher dispatcher = new MarketOutboxDispatcher(repository, publisher);
        dispatcher.dispatchPendingMessages();

        ArgumentCaptor<OffsetDateTime> nextRetryCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).markFailed(eq("outbox-2003"), nextRetryCaptor.capture(), eq("still failing"), eq(10));

        long delaySeconds = java.time.Duration.between(beforeDispatch, nextRetryCaptor.getValue()).getSeconds();
        Assertions.assertTrue(delaySeconds >= 1439 && delaySeconds <= 2161,
                "delaySeconds=" + delaySeconds);
    }
}
