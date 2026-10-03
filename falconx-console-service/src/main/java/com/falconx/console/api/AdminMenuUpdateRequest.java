package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 编辑菜单请求体（{@code code} 不可改）。
 */
public record AdminMenuUpdateRequest(
        Long parentId,
        @NotBlank @Size(max = 64) String name,
        @Size(max = 128) String icon,
        @Size(max = 255) String path,
        @NotBlank @Size(max = 128) String permissionCode,
        @NotNull Integer sortOrder,
        @NotNull Boolean isVisible
) {
}
