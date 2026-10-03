package com.falconx.identity.service.impl;

import com.falconx.identity.service.IdentityTokenBlacklistService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis 的 Token 黑名单实现。
 *
 * <p>该实现将已登出的 Access Token JTI 写入 Redis，供 gateway 在每次请求验证时检查。
 *
 * <p>Redis key 格式：{@code falconx:auth:token:blacklist:{jti}}
 * <ul>
 *   <li>value：固定字符串 {@code "1"}，仅起存在性标记作用</li>
 *   <li>TTL：由调用方传入，通常等于 Access Token 的 TTL（15 分钟）</li>
 *   <li>若 remainingTtl &lt;= 0，不写入 Redis，Token 已自然过期无需吊销</li>
 * </ul>
 */
@Service
public class RedisIdentityTokenBlacklistService implements IdentityTokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(RedisIdentityTokenBlacklistService.class);
    private static final String BLACKLIST_KEY_PREFIX = "falconx:auth:token:blacklist:";
    private static final String BLACKLIST_VALUE = "1";

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdentityTokenBlacklistService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将指定 JTI 写入 Redis 黑名单。
     *
     * <p>若 remainingTtl &lt;= 0，则跳过写入（Token 已自然过期，不需要额外黑名单记录）。
     *
     * @param jti          Access Token 的唯一标识符
     * @param remainingTtl 黑名单条目的剩余生命周期
     */
    @Override
    public void blacklistToken(String jti, Duration remainingTtl) {
        if (remainingTtl == null || remainingTtl.isNegative() || remainingTtl.isZero()) {
            log.debug("identity.token.blacklist.skipped jti={} reason=already_expired", jti);
            return;
        }
        String key = BLACKLIST_KEY_PREFIX + jti;
        stringRedisTemplate.opsForValue().set(key, BLACKLIST_VALUE, remainingTtl);
        log.info("identity.token.blacklist.recorded jti={} ttlSeconds={}", jti, remainingTtl.toSeconds());
    }
}
