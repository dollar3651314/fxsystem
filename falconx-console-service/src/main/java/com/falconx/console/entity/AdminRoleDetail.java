package com.falconx.console.entity;

import java.time.OffsetDateTime;

/**
 * 角色详情（{@code t_admin_role} 全字段视图）。
 */
public record AdminRoleDetail(
        Long id,
        String code,
        String name,
        String description,
        boolean isSystem,
        OffsetDateTime createdAt
) {
}
