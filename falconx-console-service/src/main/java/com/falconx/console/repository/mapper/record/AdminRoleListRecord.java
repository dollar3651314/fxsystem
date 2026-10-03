package com.falconx.console.repository.mapper.record;

import java.time.OffsetDateTime;

/**
 * 角色列表行（含 {@code memberCount} / {@code permissionCount} 派生字段）。
 *
 * <p>对应 [`管理端接口规范`](../../../../../../../../../../docs/api/管理端接口规范.md) §5.2.1 列表项。
 */
public record AdminRoleListRecord(
        Long id,
        String code,
        String name,
        String description,
        Integer isSystem,
        Long memberCount,
        Long permissionCount,
        OffsetDateTime createdAt
) {
}
