package com.falconx.console.entity;

import java.time.OffsetDateTime;

/**
 * STAGE-13 系统配置中心：一条动态运维配置项。
 *
 * <p>对应 {@code t_system_config} 表。所有 service 启动时从此表读取动态参数，
 * 运行时通过 Redis pub/sub 接收变更事件后更新本地 cache。
 *
 * @param id 主键
 * @param configKey 配置 key，如 {@code gateway.ratelimit.auth-per-minute}
 * @param configValue 配置当前值（按 valueType 解释；列表/对象用 JSON）
 * @param valueType {@link SystemConfigValueType}
 * @param category {@link SystemConfigCategory}
 * @param scope GLOBAL 或 SERVICE:<name>
 * @param description 人类可读描述（admin UI 显示）
 * @param defaultValue 硬编码默认值（reset 用）
 * @param validationRegex 值合法性正则（如 ^\d+$）
 * @param isSensitive 是否敏感（UI 默认隐藏，如未来加 secret key 时用）
 * @param updatedBy 最后更新的 admin user id
 * @param updatedAt 最后更新时间
 * @param createdAt 创建时间
 */
public record SystemConfigEntry(
        Long id,
        String configKey,
        String configValue,
        SystemConfigValueType valueType,
        SystemConfigCategory category,
        String scope,
        String description,
        String defaultValue,
        String validationRegex,
        boolean isSensitive,
        Long updatedBy,
        OffsetDateTime updatedAt,
        OffsetDateTime createdAt
) {
}
