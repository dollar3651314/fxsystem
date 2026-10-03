package com.falconx.trading.entity;

import java.time.OffsetDateTime;

/**
 * 交易核心入站去重记录。
 *
 * <p>该对象对应后续 `falconx_trading.t_inbox` 的最小内存模型，
 * 用于对 `wallet.deposit.confirmed / reversed` 这类低频关键事件做 `eventId` 幂等保护。
 */
public record TradingInboxMessage(
        String eventId,
        String eventType,
        OffsetDateTime processedAt
) {
}
