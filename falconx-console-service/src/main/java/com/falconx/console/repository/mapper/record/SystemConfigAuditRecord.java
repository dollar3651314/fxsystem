package com.falconx.console.repository.mapper.record;

import java.time.LocalDateTime;

public record SystemConfigAuditRecord(
        Long id,
        String configKey,
        String oldValue,
        String newValue,
        String action,
        Long operatorId,
        String operatorEmail,
        String clientIp,
        String reason,
        LocalDateTime createdAt
) {
}
