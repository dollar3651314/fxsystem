package com.falconx.identity.contract.auth;

/**
 * 认证结果响应契约。
 *
 * <p>该对象统一承载登录和刷新接口返回的 token 结果。
 *
 * @param accessToken 访问令牌
 * @param refreshToken 刷新令牌
 * @param accessTokenExpiresIn Access Token 剩余有效秒数
 * @param refreshTokenExpiresIn Refresh Token 剩余有效秒数
 * @param userStatus 当前用户状态
 * @param emailVerified 邮箱是否已完成可信度验证
 */
public record AuthTokenResponse(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresIn,
        long refreshTokenExpiresIn,
        String userStatus,
        boolean emailVerified
) {
}
