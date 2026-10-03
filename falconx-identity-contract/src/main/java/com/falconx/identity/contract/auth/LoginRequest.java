package com.falconx.identity.contract.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求契约。
 *
 * <p>该对象定义邮箱密码登录的最小输入结构。
 *
 * @param email 登录邮箱
 * @param password 明文密码，仅用于本次认证
 */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password
) {
}
