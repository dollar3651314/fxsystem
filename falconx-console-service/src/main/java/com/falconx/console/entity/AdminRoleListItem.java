package com.falconx.console.entity;

import java.time.OffsetDateTime;

/**
 * 角色列表项（含派生字段 memberCount / permissionCount）。
 */
public record AdminRoleListItem(
        Long id,
        String code,
        String name,
        String description,
        boolean isSystem,
        long memberCount,
        long permissionCount,
        OffsetDateTime createdAt
) {
}
