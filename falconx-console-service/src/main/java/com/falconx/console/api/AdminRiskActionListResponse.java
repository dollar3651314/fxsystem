package com.falconx.console.api;

import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

public record AdminRiskActionListResponse(List<Item> items, long total, int page, int size) {

    public record Item(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String symbol,
            String actionType,
            String triggerSource,
            String triggerReason,
            @JsonSerialize(using = ToStringSerializer.class) Long hedgeLogId,
            boolean isActive,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
