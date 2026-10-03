package com.falconx.identity.service.impl;

import com.falconx.identity.config.IdentityServiceProperties;
import com.falconx.identity.error.IdentityBusinessException;
import com.falconx.identity.error.IdentityErrorCode;
import com.falconx.identity.service.IdentitySecurityPolicyService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis 的身份安全策略实现。
 *
 * <p>该实现只处理 Stage 6B 必须落地的两类 IP 级防护：
 *
 * <ul>
 *   <li>登录失败计数与临时锁定</li>
 *   <li>注册频率限制</li>
 * </ul>
 */
@Service
public class RedisIdentitySecurityPolicyService implements IdentitySecurityPolicyService {

    private static final Logger log = LoggerFactory.getLogger(RedisIdentitySecurityPolicyService.class);
    private static final String LOGIN_FAILURE_KEY_PREFIX = "falconx:auth:login:fail:";
    private static final String REGISTER_RATE_LIMIT_KEY_PREFIX = "falconx:auth:register:limit:";

    private final StringRedisTemplate stringRedisTemplate;
    private final IdentityServiceProperties properties;

    public RedisIdentitySecurityPolicyService(StringRedisTemplate stringRedisTemplate,
                                              IdentityServiceProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    @Override
    public void ensureLoginAllowed(String clientIp) {
        Integer failureCount = readInteger(loginFailureKey(clientIp));
        if (failureCount != null && failureCount >= properties.getSecurity().getLoginFailureLimit()) {
            log.warn("identity.login.rate-limited clientIp={} failureCount={}", normalizeClientIp(clientIp), failureCount);
            throw new IdentityBusinessException(IdentityErrorCode.LOGIN_RATE_LIMITED);
        }
    }

    @Override
    public void recordLoginFailure(String clientIp) {
        // 2026-05-26 Sprint 5 B1：incr + expire 原本 2 RT，改用 pipelined 1 RT。
        String key = loginFailureKey(clientIp);
        incrementWithExpire(key, properties.getSecurity().getLoginLockDuration());
    }

    @Override
    public void clearLoginFailures(String clientIp) {
        stringRedisTemplate.delete(loginFailureKey(clientIp));
    }

    @Override
    public void consumeRegisterQuota(String clientIp) {
        // 2026-05-26 Sprint 5 B1：incr + expire 原本 2 RT，改用 pipelined 1 RT。
        String key = registerRateLimitKey(clientIp);
        Long requestCount = incrementWithExpire(key, properties.getSecurity().getRegisterWindow());
        if (requestCount != null && requestCount > properties.getSecurity().getRegisterLimit()) {
            log.warn("identity.register.rate-limited clientIp={} requestCount={}",
                    normalizeClientIp(clientIp),
                    requestCount);
            throw new IdentityBusinessException(IdentityErrorCode.REGISTER_RATE_LIMITED);
        }
    }

    /**
     * 2026-05-26 Sprint 5 B1：pipelined INCR + EXPIRE 单 RT。
     * 返回 incr 结果（递增后的计数）。
     */
    private Long incrementWithExpire(String key, Duration ttl) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        long ttlSeconds = Math.max(1L, ttl.getSeconds());
        List<Object> results = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            connection.stringCommands().incr(keyBytes);
            connection.keyCommands().expire(keyBytes, ttlSeconds);
            return null;
        });
        if (results == null || results.isEmpty()) {
            return null;
        }
        Object first = results.get(0);
        return first instanceof Long longValue ? longValue : null;
    }

    private Integer readInteger(String key) {
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        return Integer.parseInt(value);
    }

    private String loginFailureKey(String clientIp) {
        return LOGIN_FAILURE_KEY_PREFIX + normalizeClientIp(clientIp);
    }

    private String registerRateLimitKey(String clientIp) {
        return REGISTER_RATE_LIMIT_KEY_PREFIX + normalizeClientIp(clientIp);
    }

    private String normalizeClientIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return "unknown";
        }
        return clientIp.trim();
    }
}
