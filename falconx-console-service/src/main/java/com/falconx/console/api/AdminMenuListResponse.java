package com.falconx.console.api;

import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 阶段 1 P4 菜单列表响应（树形 / 扁平共用结构）。
 *
 * <p>对应 [`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §5.3.1。
 */
public record AdminMenuListResponse(List<Item> items) {

    public record Item(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            @JsonSerialize(using = ToStringSerializer.class) Long parentId,
            String code,
            String name,
            String icon,
            String path,
            String permissionCode,
            int sortOrder,
            boolean isVisible,
            List<Item> children
    ) {
    }
}
