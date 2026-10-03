package com.falconx.trading.api;

import java.time.LocalDateTime;
import java.util.List;

public record AdminRiskActionListResponse(List<Item> items, long total, int page, int size) {

    public record Item(
            Long id,
            String symbol,
            String actionType,
            String triggerSource,
            String triggerReason,
            Long hedgeLogId,
            boolean isActive,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
