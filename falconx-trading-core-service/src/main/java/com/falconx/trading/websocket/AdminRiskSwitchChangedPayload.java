package com.falconx.trading.websocket;

import java.time.OffsetDateTime;

/**
 * STAGE-2-REALTIME-DATA Phase 2：管理端风控开关切换 payload。
 */
public record AdminRiskSwitchChangedPayload(
        String switchKey,
        boolean enabled,
        String updatedBy,
        OffsetDateTime updatedAt
) {}
