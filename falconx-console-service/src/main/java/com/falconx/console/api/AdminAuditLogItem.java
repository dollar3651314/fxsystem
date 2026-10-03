package com.falconx.console.api;

import java.time.OffsetDateTime;

/**
 * STAGE-9-RISK-OPS-COMPLETE §12.2：审计日志条目（管理端列表 / 详情）。
 *
 * <p>id / adminUserId / targetId 用 String 表示雪花 ID 防 JS 精度丢失。
 */
public record AdminAuditLogItem(
        String id,
        String adminUserId,
        String permissionCode,
        String targetType,
        String targetId,
        String beforeValue,
        String afterValue,
        String riskLevel,
        String ip,
        String userAgent,
        OffsetDateTime occurredAt
) {
}
