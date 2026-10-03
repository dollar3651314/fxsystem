package com.falconx.console.api;

import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 阶段 1 P2 管理员列表响应。
 */
public record AdminUserListResponse(List<Item> items, long total, int page, int size) {

    public record Item(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String username,
            String realName,
            String status,           // ACTIVE / DISABLED
            boolean mustChangePassword,
            LocalDateTime lastLoginAt,
            String lastLoginIp,
            LocalDateTime createdAt,
            List<RoleRef> roles
    ) {
    }

    public record RoleRef(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name
    ) {
    }
}
