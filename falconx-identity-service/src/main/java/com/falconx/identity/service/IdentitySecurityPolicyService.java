package com.falconx.identity.service;

/**
 * 身份安全策略服务。
 *
 * <p>该服务负责收敛 Stage 6B 中与客户端 IP 相关的安全控制，
 * 包括登录失败锁定与注册频率限制。
 */
public interface IdentitySecurityPolicyService {

    /**
     * 校验当前 IP 是否允许继续执行登录。
     *
     * @param clientIp 客户端 IP
     */
    void ensureLoginAllowed(String clientIp);

    /**
     * 记录一次登录失败。
     *
     * @param clientIp 客户端 IP
     */
    void recordLoginFailure(String clientIp);

    /**
     * 清理当前 IP 的登录失败计数。
     *
     * @param clientIp 客户端 IP
     */
    void clearLoginFailures(String clientIp);

    /**
     * 消耗一次注册额度。
     *
     * @param clientIp 客户端 IP
     */
    void consumeRegisterQuota(String clientIp);
}
