package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 禁用管理员请求体（高风险）。
 */
public record AdminUserDisableRequest(
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
