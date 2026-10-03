package com.falconx.console.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * STAGE-13 t_system_config 表行映射。
 *
 * <p>Service 层 record；MyBatis 通过 constructor-based binding 注入。
 * 时间字段使用 LocalDateTime（MySQL DATETIME(3)），上层转 OffsetDateTime。
 */
public record SystemConfigRecord(
        Long id,
        String configKey,
        String configValue,
        String valueType,
        String category,
        String scope,
        String description,
        String defaultValue,
        String validationRegex,
        Boolean isSensitive,
        Long updatedBy,
        LocalDateTime updatedAt,
        LocalDateTime createdAt
) {
}
