package com.falconx.console.repository.mapper.record;

import java.time.OffsetDateTime;

/**
 * {@code t_admin_permission} 列表查询行记录（含 {@code role_count} 派生字段）。
 *
 * <p>对应 [`管理端接口规范`](../../../../../../../../../../docs/api/管理端接口规范.md) §5.4.1
 * 列表响应；{@code isHighRisk} 由 ApplicationService 按
 * {@link com.falconx.console.security.HighRiskPermissionRegistry} 静态匹配补齐。
 *
 * @param code 权限码
 * @param module 模块
 * @param action 动作
 * @param description 描述
 * @param createdAt 入字典时间
 * @param roleCount 关联角色数（{@code COUNT(t_admin_role_permission.role_id)}）；MyBatis 从
 *                  BIGINT 派生为 boxed {@link Long}，与 ResultMap javaType 对齐。
 */
public record AdminPermissionListRecord(
        String code,
        String module,
        String action,
        String description,
        OffsetDateTime createdAt,
        Long roleCount
) {
}
