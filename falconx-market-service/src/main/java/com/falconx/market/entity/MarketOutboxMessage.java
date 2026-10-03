package com.falconx.market.entity;

import java.time.OffsetDateTime;

/**
 * market-service 出站事件记录。
 *
 * <p>该对象对应 `falconx_market.t_outbox` 的领域表达，
 * 用于在 owner 事务内冻结低频市场事件的 payload、分区键与重试元数据。
 */
public record MarketOutboxMessage(
        String outboxId,
        String eventId,
        String eventType,
        String partitionKey,
        Object payload,
        MarketOutboxStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime sentAt,
        int retryCount,
        OffsetDateTime nextRetryAt,
        String lastError
) {
}
