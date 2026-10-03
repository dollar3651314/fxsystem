package com.falconx.console.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * 管理员列表行记录（不含 password_hash / mustChangePassword）。
 *
 * <p>roles[] 通过额外 selectRolesForUserIds 单独查询（避免 N+1，不在 ResultMap 内嵌套）。
 */
public record AdminUserListRecord(
        Long id,
        String username,
        String realName,
        Integer status,
        Integer mustChangePassword,
        LocalDateTime lastLoginAt,
        String lastLoginIp,
        LocalDateTime createdAt
) {
}
