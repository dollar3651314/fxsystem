package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员登录请求。
 *
 * @param username 登录用户名（3-32 字符）
 * @param password 登录密码（明文）
 */
public record AdminLoginRequest(
        @NotBlank @Size(min = 3, max = 32) String username,
        @NotBlank String password
) {
}
