package com.falconx.console.security;

import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis 的管理员 refresh token 状态存储。
 */
@Service
public class RedisAdminRefreshTokenStore implements AdminRefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RedisAdminRefreshTokenStore.class);
    private static final String ACTIVE_KEY_PREFIX = "falconx:admin:refresh-token:active:";
    private static final String USER_INDEX_KEY_PREFIX = "falconx:admin:refresh-token:user:";
    private static final String ACTIVE_VALUE = "1";

    private final StringRedisTemplate stringRedisTemplate;

    public RedisAdminRefreshTokenStore(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void register(long adminUserId, String jti, Duration remainingTtl) {
        if (jti == null || jti.isBlank() || remainingTtl == null || remainingTtl.isNegative()
                || remainingTtl.isZero()) {
            return;
        }
        stringRedisTemplate.opsForValue().set(ACTIVE_KEY_PREFIX + jti, ACTIVE_VALUE, remainingTtl);
        String userIndexKey = USER_INDEX_KEY_PREFIX + adminUserId;
        stringRedisTemplate.opsForSet().add(userIndexKey, jti);
        stringRedisTemplate.expire(userIndexKey, remainingTtl);
        log.debug("admin.refresh-token.registered userId={} jti={} ttlSeconds={}",
                adminUserId, jti, remainingTtl.toSeconds());
    }

    @Override
    public boolean isActive(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(ACTIVE_KEY_PREFIX + jti));
    }

    @Override
    public void consume(long adminUserId, String jti) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        stringRedisTemplate.delete(ACTIVE_KEY_PREFIX + jti);
        stringRedisTemplate.opsForSet().remove(USER_INDEX_KEY_PREFIX + adminUserId, jti);
        log.info("admin.refresh-token.consumed userId={} jti={}", adminUserId, jti);
    }

    @Override
    public void revokeAllByUserId(long adminUserId) {
        String userIndexKey = USER_INDEX_KEY_PREFIX + adminUserId;
        Set<String> jtis = stringRedisTemplate.opsForSet().members(userIndexKey);
        if (jtis == null || jtis.isEmpty()) {
            return;
        }
        for (String jti : jtis) {
            stringRedisTemplate.delete(ACTIVE_KEY_PREFIX + jti);
        }
        stringRedisTemplate.delete(userIndexKey);
        log.info("admin.refresh-token.revoked-all userId={} count={}", adminUserId, jtis.size());
    }
}
