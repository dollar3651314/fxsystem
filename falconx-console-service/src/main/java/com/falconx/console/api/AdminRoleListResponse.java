package com.falconx.console.api;

import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 阶段 1 P3 角色列表响应。
 */
public record AdminRoleListResponse(List<Item> items, long total, int page, int size) {

    public record Item(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String code,
            String name,
            String description,
            boolean isSystem,
            long memberCount,
            long permissionCount,
            OffsetDateTime createdAt
    ) {
    }
}
