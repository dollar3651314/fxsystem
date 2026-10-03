package com.falconx.console.security;

import java.time.Duration;

/**
 * 管理端 access token 黑名单服务。
 *
 * <p>用户主动登出 / 改密成功时把对应 access token 的 jti 加入 Redis 黑名单，
 * {@link AdminAuthenticationFilter} 在每次请求验证时检查 jti 是否被黑名单命中。
 *
 * <p>实现约束：
 * <ul>
 *   <li>Redis key 格式：{@code falconx:admin:token:blacklist:{jti}}（与 C 端
 *       {@code falconx:auth:token:blacklist:{jti}} 严格区分）</li>
 *   <li>TTL 与 access token 剩余有效期对齐（避免永久占用 Redis）</li>
 *   <li>若 remainingTtl &lt;= 0，跳过写入（token 已自然过期，无需吊销）</li>
 * </ul>
 */
public interface AdminTokenBlacklistService {

    /**
     * 把 access token 的 jti 加入黑名单。
     *
     * @param jti access token 唯一标识
     * @param remainingTtl 黑名单条目剩余生命周期，建议等于 access token 剩余 TTL
     */
    void blacklistAccessToken(String jti, Duration remainingTtl);

    /**
     * 检查 access token 的 jti 是否已被黑名单命中。
     *
     * @param jti access token 唯一标识
     * @return true 表示已黑名单（应拒绝请求）
     */
    boolean isAccessTokenBlacklisted(String jti);
}
