package com.falconx.console.entity;

import java.time.OffsetDateTime;

/**
 * STAGE-13 系统配置变更审计记录。
 *
 * <p>对应 {@code t_system_config_audit} 表。每次配置变更不可篡改地写入，永久保留。
 *
 * @param id 主键
 * @param configKey 配置 key
 * @param oldValue 变更前值（CREATE 时为 null）
 * @param newValue 变更后值（DELETE 时为 null）
 * @param action {@link SystemConfigAuditAction}
 * @param operatorId 操作的 admin user id
 * @param operatorEmail 操作时刻的 admin email 快照（即使 admin 后被删，仍可追溯）
 * @param clientIp 操作 IP
 * @param reason 变更原因（可选填）
 * @param createdAt 变更时间
 */
public record SystemConfigAudit(
        Long id,
        String configKey,
        String oldValue,
        String newValue,
        SystemConfigAuditAction action,
        Long operatorId,
        String operatorEmail,
        String clientIp,
        String reason,
        OffsetDateTime createdAt
) {

    public enum SystemConfigAuditAction {
        CREATE,
        UPDATE,
        DELETE,
        /** reset to defaultValue */
        RESET
    }
}
