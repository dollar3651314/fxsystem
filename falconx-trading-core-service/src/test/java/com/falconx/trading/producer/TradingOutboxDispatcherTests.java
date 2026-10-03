package com.falconx.trading.producer;

import com.falconx.trading.entity.TradingOutboxMessage;
import com.falconx.trading.entity.TradingOutboxStatus;
import com.falconx.trading.repository.TradingOutboxRepository;
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
 * 交易核心 Outbox 调度器测试。
 *
 * <p>这组测试不验证真实 Kafka，而是验证调度器在仓储接口层的关键协作：
 *
 * <ul>
 *   <li>事件被调度成功后，会从可发送状态迁移到 `SENT`</li>
 *   <li>事件发送失败后，会按规范进入 `FAILED / DEAD`，并记录重试元数据</li>
 * </ul>
 */
class TradingOutboxDispatcherTests {

    @Test
    void shouldDispatchPendingMessageAndMarkItAsSent() {
        TradingOutboxRepository repository = mock(TradingOutboxRepository.class);
        TradingOutboxMessage pendingMessage = new TradingOutboxMessage(
                "outbox-001",
                "evt-outbox-001",
                "trading.order.created",
                "2001",
                "payload",
                TradingOutboxStatus.PENDING,
                OffsetDateTime.now(),
                null,
                0,
                OffsetDateTime.now(),
                null
        );
        when(repository.claimDispatchableBatch(any(OffsetDateTime.class), eq(200)))
                .thenReturn(List.of(pendingMessage));
        TradingOutboxDispatcher dispatcher = new TradingOutboxDispatcher(repository, message -> {
        });

        int dispatchedCount = dispatcher.dispatchPendingMessages();

        Assertions.assertEquals(1, dispatchedCount);
        verify(repository).markSent(eq("outbox-001"), any(OffsetDateTime.class));
        verify(repository, never()).markFailed(any(), any(), any(), any(Integer.class));
    }

    @Test
    void shouldMarkMessageAsFailedAndScheduleRetryWhenPublishThrows() {
        TradingOutboxRepository repository = mock(TradingOutboxRepository.class);
        TradingOutboxMessage pendingMessage = new TradingOutboxMessage(
                "outbox-002",
                "evt-outbox-002",
                "trading.order.filled",
                "2002",
                "payload",
                TradingOutboxStatus.PENDING,
                OffsetDateTime.now(),
                null,
                0,
                OffsetDateTime.now(),
                null
        );
        when(repository.claimDispatchableBatch(any(OffsetDateTime.class), eq(200)))
                .thenReturn(List.of(pendingMessage));
        TradingOutboxEventPublisher publisher = mock(TradingOutboxEventPublisher.class);
        doThrow(new IllegalStateException("mock publish failure")).when(publisher).publish(any(TradingOutboxMessage.class));
        TradingOutboxDispatcher dispatcher = new TradingOutboxDispatcher(repository, publisher);

        int dispatchedCount = dispatcher.dispatchPendingMessages();

        ArgumentCaptor<OffsetDateTime> nextRetryCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        Assertions.assertEquals(0, dispatchedCount);
        verify(repository).markFailed(eq("outbox-002"), nextRetryCaptor.capture(), eq("mock publish failure"), eq(10));
        Assertions.assertNotNull(nextRetryCaptor.getValue());
        verify(repository, never()).markSent(any(), any());
    }

    @Test
    void shouldMoveMessageToDeadWhenRetryCountReachesThreshold() {
        TradingOutboxRepository repository = mock(TradingOutboxRepository.class);
        TradingOutboxMessage failedMessage = new TradingOutboxMessage(
                "outbox-003",
                "evt-outbox-003",
                "trading.deposit.credited",
                "2003",
                "payload",
                TradingOutboxStatus.FAILED,
                OffsetDateTime.now().minusMinutes(1),
                null,
                9,
                OffsetDateTime.now().minusSeconds(1),
                "previous failure"
        );
        when(repository.claimDispatchableBatch(any(OffsetDateTime.class), eq(200)))
                .thenReturn(List.of(failedMessage));
        TradingOutboxEventPublisher publisher = mock(TradingOutboxEventPublisher.class);
        doThrow(new IllegalStateException("still failing")).when(publisher).publish(any(TradingOutboxMessage.class));
        TradingOutboxDispatcher dispatcher = new TradingOutboxDispatcher(repository, publisher);

        dispatcher.dispatchPendingMessages();

        verify(repository).markFailed(eq("outbox-003"), any(OffsetDateTime.class), eq("still failing"), eq(10));
        verify(repository, never()).markSent(any(), any());
    }

    @Test
    void shouldApplyJitterAndThirtyMinuteCeilingForHigherRetryCount() {
        TradingOutboxRepository repository = mock(TradingOutboxRepository.class);
        TradingOutboxMessage failedMessage = new TradingOutboxMessage(
                "outbox-004",
                "evt-outbox-004",
                "trading.order.filled",
                "2004",
                "payload",
                TradingOutboxStatus.FAILED,
                OffsetDateTime.now(),
                null,
                3,
                OffsetDateTime.now(),
                "previous failure"
        );
        when(repository.claimDispatchableBatch(any(OffsetDateTime.class), eq(200)))
                .thenReturn(List.of(failedMessage));
        TradingOutboxEventPublisher publisher = mock(TradingOutboxEventPublisher.class);
        doThrow(new IllegalStateException("still failing")).when(publisher).publish(any(TradingOutboxMessage.class));

        OffsetDateTime beforeDispatch = OffsetDateTime.now();
        TradingOutboxDispatcher dispatcher = new TradingOutboxDispatcher(repository, publisher);
        dispatcher.dispatchPendingMessages();

        ArgumentCaptor<OffsetDateTime> nextRetryCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).markFailed(eq("outbox-004"), nextRetryCaptor.capture(), eq("still failing"), eq(10));

        long delaySeconds = java.time.Duration.between(beforeDispatch, nextRetryCaptor.getValue()).getSeconds();
        Assertions.assertTrue(delaySeconds >= 1439 && delaySeconds <= 2161,
                "delaySeconds=" + delaySeconds);
    }
}
