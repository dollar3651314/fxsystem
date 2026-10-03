package com.falconx.console.api;

import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 阶段 1 P5 权限点字典列表响应。
 *
 * <p>对应 [`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §5.4.1。
 */
public record AdminPermissionListResponse(List<Item> items, long total, int page, int size) {

    public record Item(
            String code,
            String module,
            String action,
            String description,
            boolean isHighRisk,
            // 关联角色数仅作展示，不需要 String 序列化
            long roleCount,
            OffsetDateTime createdAt
    ) {
    }

    /**
     * 关联角色单项（5.4.2）。雪花 roleId 必须 String 序列化（FX-071）。
     */
    public record RoleItem(
            @JsonSerialize(using = ToStringSerializer.class) long roleId,
            String roleCode,
            String roleName
    ) {
    }
}
