package com.falconx.console.repository.mapper.record;

/**
 * 管理员的角色 JOIN 行（避免列表 N+1）。
 *
 * <p>{@code userId} 用于 group by；列表 ApplicationService 用 Map&lt;userId, List&lt;Item&gt;&gt; 聚合。
 */
public record AdminUserRoleRecord(Long userId, Long roleId, String roleCode, String roleName) {
}
