package com.falconx.trading.repository.mapper.record;

import java.time.LocalDateTime;

public record TradingNotificationRecord(
        Long id,
        Long userId,
        String type,
        String templateCode,
        Integer level,
        String title,
        String body,
        String relatedKey,
        Long relatedId,
        String payloadJson,
        Integer status,
        LocalDateTime readAt,
        LocalDateTime createdAt
) {
}
