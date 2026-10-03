package com.falconx.market.service.impl;

import com.falconx.market.service.FxRateRedisCache;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link FxRateRedisCache} 的默认生产实现，基于 {@code spring-data-redis} 的
 * {@code StringRedisTemplate}。
 *
 * <p>与 market-service 其他 Redis 仓储（如 RedisMarketTradingScheduleSnapshotRepository）
 * 保持一致的客户端选型，避免在同一服务内混用多种 Redis 客户端封装。
 */
@Component
public class DefaultFxRateRedisCache implements FxRateRedisCache {

    private final StringRedisTemplate stringRedisTemplate;

    public DefaultFxRateRedisCache(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void set(String key, String value, long ttlSeconds) {
        stringRedisTemplate.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    @Override
    public long ttl(String key) {
        Long seconds = stringRedisTemplate.getExpire(key);
        return (seconds != null && seconds > 0) ? seconds : 0L;
    }
}
