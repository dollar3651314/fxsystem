package com.falconx.identity.service.model;

/**
 * 应用层内部令牌结果对象。
 *
 * @param accessToken Access Token
 * @param refreshToken Refresh Token
 * @param accessTokenExpiresIn Access Token 有效秒数
 * @param refreshTokenExpiresIn Refresh Token 有效秒数
 * @param userStatus 当前用户状态
 * @param emailVerified 邮箱是否已完成可信度验证
 */
public record AuthTokenBundle(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresIn,
        long refreshTokenExpiresIn,
        String userStatus,
        boolean emailVerified
) {
}
