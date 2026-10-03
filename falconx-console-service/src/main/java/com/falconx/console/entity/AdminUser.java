package com.falconx.console.entity;

import java.time.OffsetDateTime;

/**
 * 管理员领域实体。
 *
 * <p>对应 {@code t_admin_user} 表的领域视图，密码哈希仅在登录 / 改密链路内部访问，
 * 严禁通过任何 REST 响应或日志输出。
 *
 * @param id 雪花 ID
 * @param username 登录用户名
 * @param passwordHash BCrypt 加密的密码（{@code $2a$<strength>$<...>}）
 * @param realName 真实姓名
 * @param status {@link AdminUserStatus#ACTIVE} 或 {@link AdminUserStatus#DISABLED}
 * @param mustChangePassword true 表示必须先修改密码
 * @param lastLoginAt 最后登录时间
 * @param lastLoginIp 最后登录 IP
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record AdminUser(
        Long id,
        String username,
        String passwordHash,
        String realName,
        AdminUserStatus status,
        boolean mustChangePassword,
        OffsetDateTime lastLoginAt,
        String lastLoginIp,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
