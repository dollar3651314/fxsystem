package com.falconx.console.api;

import java.time.OffsetDateTime;

public record AdminRiskSwitchUpdateResponse(
        String key,
        boolean enabled,
        OffsetDateTime updatedAt,
        String updatedBy
) {
}
