package com.falconx.console.security;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis 的管理端 token 黑名单实现。
 *
 * <p>Redis key 前缀使用 {@code falconx:admin:token:blacklist:}，与 C 端
 * {@code falconx:auth:token:blacklist:} 严格区分，保证两端 token 命名空间隔离。
 */
@Service
public class RedisAdminTokenBlacklistService implements AdminTokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(RedisAdminTokenBlacklistService.class);
    private static final String BLACKLIST_KEY_PREFIX = "falconx:admin:token:blacklist:";
    private static final String BLACKLIST_VALUE = "1";

    private final StringRedisTemplate stringRedisTemplate;

    public RedisAdminTokenBlacklistService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void blacklistAccessToken(String jti, Duration remainingTtl) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        if (remainingTtl == null || remainingTtl.isNegative() || remainingTtl.isZero()) {
            log.debug("admin.token.blacklist.skipped jti={} reason=already_expired", jti);
            return;
        }
        String key = BLACKLIST_KEY_PREFIX + jti;
        stringRedisTemplate.opsForValue().set(key, BLACKLIST_VALUE, remainingTtl);
        log.info("admin.token.blacklist.recorded jti={} ttlSeconds={}", jti, remainingTtl.toSeconds());
    }

    @Override
    public boolean isAccessTokenBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        String key = BLACKLIST_KEY_PREFIX + jti;
        Boolean exists = stringRedisTemplate.hasKey(key);
        return Boolean.TRUE.equals(exists);
    }
}
