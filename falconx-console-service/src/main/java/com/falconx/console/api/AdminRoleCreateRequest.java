package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建角色请求体。
 */
public record AdminRoleCreateRequest(
        @NotBlank @Pattern(regexp = "^[A-Z0-9_]{2,32}$") String code,
        @NotBlank @Size(max = 64) String name,
        @Size(max = 255) String description
) {
}
