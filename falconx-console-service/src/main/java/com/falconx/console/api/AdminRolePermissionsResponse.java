package com.falconx.console.api;

import java.util.List;

/**
 * 角色权限码集合响应（GET /admin/admin-roles/&#123;id&#125;/permissions）。
 */
public record AdminRolePermissionsResponse(List<String> permissionCodes) {
}
