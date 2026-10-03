package com.falconx.trading.repository.mapper.record;

import java.time.LocalDateTime;

public record TradingNotificationTemplateRecord(
        String code,
        String titleTemplate,
        String bodyTemplate,
        Integer level,
        String channels,
        String description,
        Integer enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
