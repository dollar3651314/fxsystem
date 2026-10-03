package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 管理员修改密码请求（首次强制改密 + 主动修改共用）。
 *
 * @param oldPassword 旧密码（明文）
 * @param newPassword 新密码（明文，前端先做强度提示，后端按 {@link com.falconx.console.security.AdminPasswordPolicyValidator} 校验）
 */
public record AdminChangePasswordRequest(
        @NotBlank String oldPassword,
        @NotBlank String newPassword
) {
}
