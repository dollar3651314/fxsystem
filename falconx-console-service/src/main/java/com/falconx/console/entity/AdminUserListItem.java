package com.falconx.console.entity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员列表项（含 roles[] 派生集合）。
 *
 * @param roles 已绑定的角色列表
 */
public record AdminUserListItem(
        Long id,
        String username,
        String realName,
        Integer status,
        boolean mustChangePassword,
        LocalDateTime lastLoginAt,
        String lastLoginIp,
        LocalDateTime createdAt,
        List<AdminRole> roles
) {
}
