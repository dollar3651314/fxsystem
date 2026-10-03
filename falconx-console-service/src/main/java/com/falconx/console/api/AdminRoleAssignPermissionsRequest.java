package com.falconx.console.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 角色权限分配请求体（高风险，全量替换语义）。
 */
public record AdminRoleAssignPermissionsRequest(
        @NotNull List<String> permissionCodes,
        @NotNull @Size(min = 10, max = 500) String reason
) {
}
