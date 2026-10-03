package com.falconx.console.api;

import java.util.List;

/**
 * GET /admin/me/permissions 响应。
 *
 * <p>SUPER_ADMIN 时 {@code isSuperAdmin=true} + {@code permissions} 等于字典全集；
 * 普通角色时 {@code isSuperAdmin=false} + {@code permissions} 等于角色权限并集（去重）。
 *
 * @param permissions 权限码列表
 * @param isSuperAdmin 是否超级管理员
 */
public record AdminMePermissionsResponse(List<String> permissions, boolean isSuperAdmin) {
}
