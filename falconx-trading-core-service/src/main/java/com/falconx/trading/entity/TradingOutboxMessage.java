package com.falconx.trading.entity;

import java.time.OffsetDateTime;

/**
 * 交易核心出站事件记录。
 *
 * <p>该对象对应后续 `falconx_trading.t_outbox` 的最小内存模型。
 * 当前阶段虽然还未接入真实 MySQL `t_outbox`，但字段已经补齐到
 * “可表达重试 / 退避 / 死信”的程度，避免后续切换真实存储时再次改模型。
 */
public record TradingOutboxMessage(
        String outboxId,
        String eventId,
        String eventType,
        String partitionKey,
        Object payload,
        TradingOutboxStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime sentAt,
        int retryCount,
        OffsetDateTime nextRetryAt,
        String lastError
) {
}
