package com.falconx.console.security;

/**
 * 管理员登录失败计数 + 锁定服务。
 *
 * <p>实现约束（按 docs/api/管理端接口规范.md §1.5 + DESIGN §10）：
 *
 * <ul>
 *   <li>失败计数 key：{@code falconx:admin:login-failure:{username}}</li>
 *   <li>锁定 key：{@code falconx:admin:login-locked:{username}}</li>
 *   <li>失败计数滑动窗口对齐配置 {@code falconx.console.security.login-lock-duration}</li>
 *   <li>达到 {@code login-failure-limit} 时锁定 {@code login-lock-duration}</li>
 *   <li>登录成功后清空失败计数</li>
 * </ul>
 */
public interface AdminLoginAttemptService {

    /**
     * 检查 username 是否处于锁定状态。
     *
     * @param username 登录用户名
     * @return true 表示锁定（应抛 90006）
     */
    boolean isLocked(String username);

    /**
     * 记录一次登录失败 + 必要时触发锁定。
     *
     * @param username 登录用户名
     * @return 是否触发了锁定（达到阈值时返回 true，调用方应抛 90006）
     */
    boolean recordFailureAndCheckLock(String username);

    /**
     * 登录成功后清空失败计数。
     *
     * @param username 登录用户名
     */
    void clearFailures(String username);
}
