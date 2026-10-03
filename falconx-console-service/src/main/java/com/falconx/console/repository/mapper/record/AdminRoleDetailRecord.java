package com.falconx.console.repository.mapper.record;

import java.time.OffsetDateTime;

/**
 * 角色详情行（{@code t_admin_role} 全字段）。
 */
public record AdminRoleDetailRecord(
        Long id,
        String code,
        String name,
        String description,
        Integer isSystem,
        OffsetDateTime createdAt
) {
}
