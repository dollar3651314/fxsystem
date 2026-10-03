package com.falconx.console.security;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 管理端 token 签发 / 校验抽象。
 *
 * <p>本接口仅提供 token 工具能力，不承担数据库 / Redis 持久化（持久化由
 * {@link AdminTokenBlacklistService} 与 {@code AdminRefreshTokenStore}（R9.5 引入）负责）。
 *
 * <p>本接口与 {@code falconx-identity-service} 的 {@code IdentityTokenService} 完全独立：
 * <ul>
 *   <li>独立 RSA 私钥（{@code falconx.console.key-pair.*}）</li>
 *   <li>独立 issuer（{@code falconx-console-service}）</li>
 *   <li>独立 jti 命名空间（与 C 端 jti 不会冲突）</li>
 * </ul>
 */
public interface AdminTokenSupport {

    /**
     * 签发 access token。
     *
     * @param adminUserId 管理员主键
     * @param username 登录用户名
     * @param roles 当前角色 code 集合
     * @param mustChangePassword 是否首次登录强制改密
     * @return 已签发的 access token 三元组
     */
    IssuedToken issueAccessToken(long adminUserId, String username, List<String> roles, boolean mustChangePassword);

    /**
     * 签发 refresh token。
     *
     * @param adminUserId 管理员主键
     * @return 已签发的 refresh token 三元组
     */
    IssuedToken issueRefreshToken(long adminUserId);

    /**
     * 校验 access token，返回 {@link AdminPrincipal}。
     *
     * @param accessToken access token 字符串
     * @return 已认证的管理员上下文
     * @throws com.falconx.console.error.AdminBusinessException 失败时抛
     *         {@link com.falconx.console.error.AdminErrorCode#ADMIN_TOKEN_EXPIRED} 或
     *         {@link com.falconx.console.error.AdminErrorCode#ADMIN_TOKEN_INVALID}
     */
    AdminPrincipal parseAndVerifyAccessToken(String accessToken);

    /**
     * 校验 refresh token，返回其中的 jti / userId / expiresAt。
     *
     * @param refreshToken refresh token 字符串
     * @return 已校验的 refresh token 信息
     * @throws com.falconx.console.error.AdminBusinessException 失败时抛
     *         {@link com.falconx.console.error.AdminErrorCode#ADMIN_TOKEN_EXPIRED} 或
     *         {@link com.falconx.console.error.AdminErrorCode#ADMIN_TOKEN_INVALID}
     */
    ValidatedRefreshToken parseAndVerifyRefreshToken(String refreshToken);

    /**
     * 已签发 token 的最小信息。
     *
     * @param token JWT 字符串
     * @param jti token 唯一标识
     * @param expiresAt 过期时间（UTC）
     * @param ttlSeconds TTL 秒数（用于响应体 expiresIn 字段）
     */
    record IssuedToken(String token, String jti, OffsetDateTime expiresAt, long ttlSeconds) {
    }

    /**
     * 已校验 refresh token 的最小信息。
     *
     * @param adminUserId 管理员主键
     * @param jti refresh token 唯一标识
     * @param expiresAt 过期时间（UTC）
     */
    record ValidatedRefreshToken(long adminUserId, String jti, OffsetDateTime expiresAt) {
    }
}
