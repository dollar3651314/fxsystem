package com.falconx.wallet.producer;

import com.falconx.wallet.contract.event.WalletDepositConfirmedEventPayload;
import com.falconx.wallet.contract.event.WalletDepositDetectedEventPayload;
import com.falconx.wallet.contract.event.WalletDepositReversedEventPayload;
import com.falconx.wallet.contract.event.WalletWithdrawBroadcastedEventPayload;
import com.falconx.wallet.contract.event.WalletWithdrawConfirmedEventPayload;
import com.falconx.wallet.contract.event.WalletWithdrawFailedEventPayload;
import com.falconx.wallet.entity.WalletOutboxMessage;
import com.falconx.wallet.entity.WalletOutboxStatus;
import com.falconx.wallet.repository.WalletOutboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 基于 Outbox 的钱包事件发布实现。
 *
 * <p>该实现用于把应用层的“发布事件”动作转换为“事务内写 `t_outbox`”，
 * 避免在业务事务尚未提交时直接同步发送 Kafka。
 */
public class OutboxBackedWalletEventPublisher implements WalletEventPublisher {

    private final WalletOutboxRepository walletOutboxRepository;

    public OutboxBackedWalletEventPublisher(WalletOutboxRepository walletOutboxRepository) {
        this.walletOutboxRepository = walletOutboxRepository;
    }

    @Override
    public void publishDepositDetected(WalletDepositDetectedEventPayload payload) {
        enqueue(
                "wallet.deposit.detected",
                payload.chain() + ":" + payload.txHash(),
                stableEventId("wallet-detected", payload.walletTxId()),
                payload,
                payload.detectedAt()
        );
    }

    @Override
    public void publishDepositConfirmed(WalletDepositConfirmedEventPayload payload) {
        enqueue(
                "wallet.deposit.confirmed",
                payload.chain() + ":" + payload.txHash(),
                stableEventId("wallet-confirmed", payload.walletTxId()),
                payload,
                payload.confirmedAt()
        );
    }

    @Override
    public void publishDepositReversed(WalletDepositReversedEventPayload payload) {
        enqueue(
                "wallet.deposit.reversed",
                payload.chain() + ":" + payload.txHash(),
                stableEventId("wallet-reversed", payload.walletTxId()),
                payload,
                payload.reversedAt()
        );
    }

    @Override
    public void publishWithdrawBroadcasted(WalletWithdrawBroadcastedEventPayload payload) {
        enqueue(
                "wallet.withdraw.broadcast",
                String.valueOf(payload.userId()),
                stableEventId("wallet-withdraw-broadcast", payload.withdrawId()),
                payload,
                payload.broadcastAt()
        );
    }

    @Override
    public void publishWithdrawConfirmed(WalletWithdrawConfirmedEventPayload payload) {
        enqueue(
                "wallet.withdraw.confirmed",
                String.valueOf(payload.userId()),
                stableEventId("wallet-withdraw-confirmed", payload.withdrawId()),
                payload,
                payload.confirmedAt()
        );
    }

    @Override
    public void publishWithdrawFailed(WalletWithdrawFailedEventPayload payload) {
        enqueue(
                "wallet.withdraw.failed",
                String.valueOf(payload.userId()),
                stableEventId("wallet-withdraw-failed", payload.withdrawId()),
                payload,
                payload.failedAt()
        );
    }

    private void enqueue(String eventType,
                         String partitionKey,
                         String eventId,
                         Object payload,
                         OffsetDateTime occurredAt) {
        walletOutboxRepository.save(new WalletOutboxMessage(
                null,
                eventId,
                eventType,
                partitionKey,
                payload,
                WalletOutboxStatus.PENDING,
                occurredAt,
                null,
                0,
                occurredAt,
                null
        ));
    }

    private String stableEventId(String prefix, Long walletTxId) {
        String identity = walletTxId == null ? "null" : walletTxId.toString();
        return prefix + ":" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }
}
