package com.falconx.console.api;

import java.time.OffsetDateTime;
import java.util.List;

public record AdminTradingRiskSwitchListResponse(List<Item> items) {

    public record Item(
            String key,
            boolean enabled,
            String reason,
            String updatedBy,
            OffsetDateTime updatedAt
    ) {
    }
}
