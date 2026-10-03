package com.falconx.console.api;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 单菜单详情 / CUD 响应。
 */
public record AdminMenuDetailResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        @JsonSerialize(using = ToStringSerializer.class) Long parentId,
        String code,
        String name,
        String icon,
        String path,
        String permissionCode,
        int sortOrder,
        boolean isVisible
) {
}
