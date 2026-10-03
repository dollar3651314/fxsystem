package com.falconx.console.entity;

/**
 * 权限点关联的角色引用。
 *
 * <p>对应 [`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §5.4.2
 * `GET /admin/admin-permissions/{code}/roles` 响应单项。
 *
 * @param roleId 角色主键
 * @param roleCode 角色 code
 * @param roleName 角色显示名
 */
public record AdminPermissionRoleRef(long roleId, String roleCode, String roleName) {
}
