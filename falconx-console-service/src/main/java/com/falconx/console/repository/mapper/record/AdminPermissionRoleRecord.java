package com.falconx.console.repository.mapper.record;

/**
 * 权限点关联角色行记录。
 *
 * <p>对应 [`管理端接口规范`](../../../../../../../../../../docs/api/管理端接口规范.md) §5.4.2
 * `GET /admin/admin-permissions/{code}/roles` 响应单项。
 *
 * @param roleId 角色主键
 * @param roleCode 角色 code
 * @param roleName 角色显示名
 */
public record AdminPermissionRoleRecord(Long roleId, String roleCode, String roleName) {
}
