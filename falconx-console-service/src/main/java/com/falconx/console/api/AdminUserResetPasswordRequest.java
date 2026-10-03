package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重置管理员密码请求体（高风险）。
 */
public record AdminUserResetPasswordRequest(
        /** 显式密码（可空，缺省时后端生成 16 位随机）。 */
        String password,
        Boolean forceChangePassword,
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
