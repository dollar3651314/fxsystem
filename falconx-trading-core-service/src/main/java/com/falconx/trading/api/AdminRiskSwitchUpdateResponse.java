package com.falconx.trading.api;

import java.time.OffsetDateTime;

/**
 * 管理端风控开关切换响应。
 */
public record AdminRiskSwitchUpdateResponse(
        String key,
        boolean enabled,
        OffsetDateTime updatedAt,
        String updatedBy
) {
}
