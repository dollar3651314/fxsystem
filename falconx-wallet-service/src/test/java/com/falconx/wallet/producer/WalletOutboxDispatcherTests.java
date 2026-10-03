package com.falconx.wallet.producer;

import com.falconx.wallet.entity.WalletOutboxMessage;
import com.falconx.wallet.entity.WalletOutboxStatus;
import com.falconx.wallet.repository.WalletOutboxRepository;
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
 * wallet-service Outbox 调度器测试。
 *
 * <p>该测试验证钱包低频关键事件已经切换到
 * “认领批次 -> Kafka 发送 -> 成功回写 / 失败退避”的调度语义。
 */
class WalletOutboxDispatcherTests {

    @Test
    void shouldDispatchPendingWalletOutboxMessageAndMarkItAsSent() {
        WalletOutboxRepository repository = mock(WalletOutboxRepository.class);
        WalletOutboxMessage pendingMessage = new WalletOutboxMessage(
                "outbox-1001",
                "evt-wallet-1001",
                "wallet.deposit.confirmed",
                "ETH:0xabc",
                "payload",
                WalletOutboxStatus.PENDING,
                OffsetDateTime.now(),
                null,
                0,
                OffsetDateTime.now(),
                null
        );
        when(repository.claimDispatchableBatch(any(OffsetDateTime.class), eq(200)))
                .thenReturn(List.of(pendingMessage));

        WalletOutboxDispatcher dispatcher = new WalletOutboxDispatcher(repository, message -> {
        });

        int dispatchedCount = dispatcher.dispatchPendingMessages();

        Assertions.assertEquals(1, dispatchedCount);
        verify(repository).markSent(eq("outbox-1001"), any(OffsetDateTime.class));
        verify(repository, never()).markFailed(any(), any(), any(), any(Integer.class));
    }

    @Test
    void shouldMarkWalletOutboxMessageAsFailedWhenPublishThrows() {
        WalletOutboxRepository repository = mock(WalletOutboxRepository.class);
        WalletOutboxMessage pendingMessage = new WalletOutboxMessage(
                "outbox-1002",
                "evt-wallet-1002",
                "wallet.deposit.reversed",
                "ETH:0xdef",
                "payload",
                WalletOutboxStatus.PENDING,
                OffsetDateTime.now(),
                null,
                0,
                OffsetDateTime.now(),
                null
        );
        when(repository.claimDispatchableBatch(any(OffsetDateTime.class), eq(200)))
                .thenReturn(List.of(pendingMessage));
        WalletOutboxEventPublisher publisher = mock(WalletOutboxEventPublisher.class);
        doThrow(new IllegalStateException("mock publish failure")).when(publisher).publish(any(WalletOutboxMessage.class));

        WalletOutboxDispatcher dispatcher = new WalletOutboxDispatcher(repository, publisher);
        int dispatchedCount = dispatcher.dispatchPendingMessages();

        ArgumentCaptor<OffsetDateTime> nextRetryCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        Assertions.assertEquals(0, dispatchedCount);
        verify(repository).markFailed(eq("outbox-1002"), nextRetryCaptor.capture(), eq("mock publish failure"), eq(10));
        Assertions.assertNotNull(nextRetryCaptor.getValue());
        verify(repository, never()).markSent(any(), any());
    }

    @Test
    void shouldApplyJitterAndThirtyMinuteCeilingForHigherRetryCount() {
        WalletOutboxRepository repository = mock(WalletOutboxRepository.class);
        WalletOutboxMessage pendingMessage = new WalletOutboxMessage(
                "outbox-1003",
                "evt-wallet-1003",
                "wallet.deposit.confirmed",
                "ETH:0xghi",
                "payload",
                WalletOutboxStatus.FAILED,
                OffsetDateTime.now(),
                null,
                3,
                OffsetDateTime.now(),
                "previous failure"
        );
        when(repository.claimDispatchableBatch(any(OffsetDateTime.class), eq(200)))
                .thenReturn(List.of(pendingMessage));
        WalletOutboxEventPublisher publisher = mock(WalletOutboxEventPublisher.class);
        doThrow(new IllegalStateException("still failing")).when(publisher).publish(any(WalletOutboxMessage.class));

        OffsetDateTime beforeDispatch = OffsetDateTime.now();
        WalletOutboxDispatcher dispatcher = new WalletOutboxDispatcher(repository, publisher);
        dispatcher.dispatchPendingMessages();

        ArgumentCaptor<OffsetDateTime> nextRetryCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository).markFailed(eq("outbox-1003"), nextRetryCaptor.capture(), eq("still failing"), eq(10));

        long delaySeconds = java.time.Duration.between(beforeDispatch, nextRetryCaptor.getValue()).getSeconds();
        Assertions.assertTrue(delaySeconds >= 1439 && delaySeconds <= 2161,
                "delaySeconds=" + delaySeconds);
    }
}
