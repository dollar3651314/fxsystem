package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 编辑角色请求体（{@code code} 不可改）。
 */
public record AdminRoleUpdateRequest(
        @NotBlank @Size(max = 64) String name,
        @Size(max = 255) String description
) {
}
