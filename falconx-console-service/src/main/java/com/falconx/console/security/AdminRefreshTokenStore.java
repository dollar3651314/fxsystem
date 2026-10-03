package com.falconx.console.security;

import java.time.Duration;

/**
 * 管理员 refresh token 状态存储。
 *
 * <p>实现一次性轮换语义：refresh 接口换新后旧 jti 立即失效；改密 / 登出后吊销该用户全部 refresh。
 *
 * <p>实现约束：
 * <ul>
 *   <li>active 状态 key：{@code falconx:admin:refresh-token:active:{jti}}</li>
 *   <li>用户索引 key：{@code falconx:admin:refresh-token:user:{adminUserId}}</li>
 *   <li>active key TTL 与 refresh token TTL 对齐</li>
 *   <li>用户索引使用 Redis Set 持有该用户当前所有有效 refresh jti，便于改密 / 登出时批量吊销</li>
 * </ul>
 */
public interface AdminRefreshTokenStore {

    /**
     * 注册一个新的 refresh token jti（issue 时调用）。
     *
     * @param adminUserId 管理员主键
     * @param jti refresh token 唯一标识
     * @param remainingTtl refresh token 剩余 TTL
     */
    void register(long adminUserId, String jti, Duration remainingTtl);

    /**
     * 校验 refresh jti 是否仍有效。
     *
     * @param jti refresh token 唯一标识
     * @return true 表示仍有效（未消费、未吊销）
     */
    boolean isActive(String jti);

    /**
     * 标记 refresh jti 已消费（refresh 成功后旧 jti 立即失效）。
     *
     * @param adminUserId 管理员主键
     * @param jti refresh token 唯一标识
     */
    void consume(long adminUserId, String jti);

    /**
     * 吊销指定管理员的所有 refresh token（改密 / 登出全设备调用）。
     *
     * @param adminUserId 管理员主键
     */
    void revokeAllByUserId(long adminUserId);
}
