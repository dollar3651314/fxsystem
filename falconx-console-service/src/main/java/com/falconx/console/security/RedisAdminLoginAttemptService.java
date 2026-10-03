package com.falconx.console.security;

import com.falconx.console.config.ConsoleServiceProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis 的管理员登录失败计数 + 锁定实现。
 */
@Service
public class RedisAdminLoginAttemptService implements AdminLoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(RedisAdminLoginAttemptService.class);
    private static final String FAILURE_KEY_PREFIX = "falconx:admin:login-failure:";
    private static final String LOCKED_KEY_PREFIX = "falconx:admin:login-locked:";
    private static final String LOCKED_VALUE = "1";

    private final StringRedisTemplate stringRedisTemplate;
    private final ConsoleServiceProperties properties;

    public RedisAdminLoginAttemptService(StringRedisTemplate stringRedisTemplate,
                                         ConsoleServiceProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    @Override
    public boolean isLocked(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(LOCKED_KEY_PREFIX + username));
    }

    @Override
    public boolean recordFailureAndCheckLock(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        String failureKey = FAILURE_KEY_PREFIX + username;
        Long count = stringRedisTemplate.opsForValue().increment(failureKey);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(failureKey, properties.getSecurity().getLoginLockDuration());
        }
        int limit = properties.getSecurity().getLoginFailureLimit();
        if (count != null && count >= limit) {
            Duration lockDuration = properties.getSecurity().getLoginLockDuration();
            stringRedisTemplate.opsForValue().set(LOCKED_KEY_PREFIX + username, LOCKED_VALUE, lockDuration);
            stringRedisTemplate.delete(failureKey);
            log.warn("admin.login.locked username={} failures={} durationSeconds={}",
                    username, count, lockDuration.toSeconds());
            return true;
        }
        log.info("admin.login.failure.recorded username={} count={} limit={}", username, count, limit);
        return false;
    }

    @Override
    public void clearFailures(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        stringRedisTemplate.delete(FAILURE_KEY_PREFIX + username);
    }
}
