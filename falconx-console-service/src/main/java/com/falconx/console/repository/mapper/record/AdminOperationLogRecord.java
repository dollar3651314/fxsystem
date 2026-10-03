package com.falconx.console.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * {@code t_admin_operation_log} 行记录。
 *
 * @param id 雪花 ID
 * @param adminUserId 操作管理员 ID
 * @param permissionCode 触发的权限码
 * @param targetType 操作目标类型（user / withdraw / symbol / ...）
 * @param targetId 操作目标 ID
 * @param beforeValue 操作前 JSON 快照
 * @param afterValue 操作后 JSON 快照
 * @param riskLevel LOW / MEDIUM / HIGH_RISK
 * @param ip 操作来源 IP
 * @param userAgent 操作来源 User-Agent
 * @param occurredAt 操作发生时间
 */
public record AdminOperationLogRecord(
        Long id,
        Long adminUserId,
        String permissionCode,
        String targetType,
        String targetId,
        String beforeValue,
        String afterValue,
        String riskLevel,
        String ip,
        String userAgent,
        LocalDateTime occurredAt
) {
}
