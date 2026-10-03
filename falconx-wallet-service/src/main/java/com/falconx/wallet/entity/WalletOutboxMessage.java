package com.falconx.wallet.entity;

import java.time.OffsetDateTime;

/**
 * wallet-service 出站事件记录。
 *
 * <p>该对象对应 `falconx_wallet.t_outbox` 的领域表达，
 * 用于在钱包事务提交前冻结低频关键事件的类型、分区键、payload 与重试状态。
 */
public record WalletOutboxMessage(
        String outboxId,
        String eventId,
        String eventType,
        String partitionKey,
        Object payload,
        WalletOutboxStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime sentAt,
        int retryCount,
        OffsetDateTime nextRetryAt,
        String lastError
) {
}
