package com.falconx.identity.service;

import java.time.Duration;

/**
 * 身份 Token 黑名单服务。
 *
 * <p>该服务负责在用户主动登出后将对应的 Access Token JTI 加入黑名单，
 * 使 gateway 在后续验证时拒绝已吊销的令牌。
 *
 * <p>实现约束：
 * <ul>
 *   <li>黑名单存储在 Redis 中，key 格式为 {@code falconx:auth:token:blacklist:{jti}}</li>
 *   <li>TTL 与 Access Token 的剩余有效期保持一致，避免永久占用存储</li>
 *   <li>若 TTL &lt;= 0，不写入 Redis（Token 已自然过期，无需额外黑名单）</li>
 * </ul>
 */
public interface IdentityTokenBlacklistService {

    /**
     * 将指定 JTI 的 Access Token 加入黑名单。
     *
     * @param jti          Access Token 的唯一标识符（JWT ID）
     * @param remainingTtl 黑名单条目的剩余生命周期，建议等于 Token 剩余有效期
     */
    void blacklistToken(String jti, Duration remainingTtl);
}
