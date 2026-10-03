package com.falconx.console.entity;

import java.time.OffsetDateTime;

/**
 * 权限点字典列表项（含派生字段）。
 *
 * <p>对应 [`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §5.4.1
 * 列表响应单项；{@code isHighRisk} 由 ApplicationService 按
 * {@link com.falconx.console.security.HighRiskPermissionRegistry} 静态匹配补齐，不在 DB 字段中。
 *
 * @param code 权限码
 * @param module 模块
 * @param action 动作
 * @param description 描述
 * @param createdAt 入字典时间
 * @param roleCount 关联角色数
 */
public record AdminPermissionListItem(
        String code,
        String module,
        String action,
        String description,
        OffsetDateTime createdAt,
        long roleCount
) {
}
