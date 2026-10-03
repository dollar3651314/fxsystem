package com.falconx.console.api;

import java.time.OffsetDateTime;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 阶段 1 P3 角色详情 / CUD 响应（共用结构）。
 */
public record AdminRoleDetailResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String code,
        String name,
        String description,
        boolean isSystem,
        OffsetDateTime createdAt
) {
}
