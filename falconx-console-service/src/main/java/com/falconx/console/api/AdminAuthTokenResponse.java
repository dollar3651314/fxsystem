package com.falconx.console.api;

import java.util.List;

/**
 * 管理员登录 / 刷新成功响应。
 *
 * <p>字段对齐 [`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §2.1 §2.2：
 *
 * @param adminUserId 管理员主键
 * @param username 登录用户名
 * @param realName 真实姓名（可空）
 * @param roles 角色 code 列表（如 {@code ["SUPER_ADMIN"]}）
 * @param mustChangePassword 是否必须先修改密码
 * @param accessToken access token JWT
 * @param refreshToken refresh token JWT
 * @param accessTokenExpiresIn access token TTL（秒）
 * @param refreshTokenExpiresIn refresh token TTL（秒）
 */
public record AdminAuthTokenResponse(
        long adminUserId,
        String username,
        String realName,
        List<String> roles,
        boolean mustChangePassword,
        String accessToken,
        String refreshToken,
        long accessTokenExpiresIn,
        long refreshTokenExpiresIn
) {
}
