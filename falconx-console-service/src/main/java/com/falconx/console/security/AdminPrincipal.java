package com.falconx.console.security;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 当前已认证管理员的最小上下文。
 *
 * <p>由 {@link AdminAuthenticationFilter} 从 access token 解析后注入到请求作用域，
 * 供 controller / service 层通过 {@link AdminSecurityContextHolder} 获取。
 *
 * <p>不包含密码哈希、refresh token、操作日志等敏感字段。
 *
 * @param adminUserId 管理员主键
 * @param username 登录用户名
 * @param roles 当前管理员的角色 code 集合（如 {@code ["SUPER_ADMIN"]}、{@code ["FINANCE","KYC_REVIEWER"]}）
 * @param jti 当前 access token 的 jti（用于 logout 时加入黑名单）
 * @param expiresAt access token 过期时间（UTC）
 * @param remainingTtl access token 剩余 TTL（用于黑名单 TTL 对齐）
 * @param mustChangePassword 是否必须先修改密码（{@code true} 时除 change-password / logout 外的接口应被拦截）
 */
public record AdminPrincipal(
        long adminUserId,
        String username,
        List<String> roles,
        String jti,
        OffsetDateTime expiresAt,
        Duration remainingTtl,
        boolean mustChangePassword
) {

    /**
     * 当前管理员是否包含 SUPER_ADMIN 角色（启动时 SUPER_ADMIN 自动放行所有 {@code @RequiresPermission}）。
     *
     * @return true 表示是超级管理员
     */
    public boolean isSuperAdmin() {
        return roles != null && roles.contains("SUPER_ADMIN");
    }
}
