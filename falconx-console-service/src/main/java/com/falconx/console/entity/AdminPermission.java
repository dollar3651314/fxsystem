package com.falconx.console.entity;

/**
 * 管理后台权限点。
 *
 * <p>对应 {@code t_admin_permission} 字典表的领域视图。字段命名遵循
 * [`管理端架构`](../../../../../../../../docs/architecture/管理端架构.md) §2.2 {@code {module}:{action}}。
 *
 * @param code 权限码（如 {@code customer:view}）
 * @param module 所属模块
 * @param action 动作
 * @param description 描述
 */
public record AdminPermission(String code, String module, String action, String description) {
}
