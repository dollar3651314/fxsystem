package com.falconx.trading.websocket;

import java.time.OffsetDateTime;

/**
 * STAGE-2-REALTIME-DATA Phase 2：管理端风控动作激活/停用 payload。
 */
public record AdminRiskActionChangedPayload(
        Long actionId,
        String symbol,
        String actionType,
        String triggerSource,
        String triggerReason,
        boolean active,
        OffsetDateTime occurredAt
) {}
