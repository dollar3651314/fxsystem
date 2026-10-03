package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建菜单请求体。
 */
public record AdminMenuCreateRequest(
        Long parentId,
        @NotBlank @Pattern(regexp = "^[A-Z0-9_]{2,64}$") String code,
        @NotBlank @Size(max = 64) String name,
        @Size(max = 128) String icon,
        @Size(max = 255) String path,
        @NotBlank @Size(max = 128) String permissionCode,
        @NotNull Integer sortOrder,
        @NotNull Boolean isVisible
) {
}
