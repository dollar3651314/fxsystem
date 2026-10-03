package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingRiskSwitch;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 风控开关 Redis 缓存。
 *
 * <p>键模板：{@code falconx:trading:risk:<switchKey>}，值为 {@code "0" / "1"}。
 *
 * <p>QuoteDrivenEngine 在每 tick 通过 {@link #isEnabled(String, boolean)} 读取，
 * 本地 5 秒缓存避免每次都打 Redis；管理员切换时通过 application service 主动刷新。
 */
@Repository
public class RedisTradingRiskSwitchCache {

    private static final String KEY_PREFIX = "falconx:trading:risk:";
    private static final Duration LOCAL_TTL = Duration.ofSeconds(5);

    private final StringRedisTemplate stringRedisTemplate;
    private final ConcurrentHashMap<String, CachedBool> localCache = new ConcurrentHashMap<>();

    public RedisTradingRiskSwitchCache(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 读取开关，命中本地 5 秒缓存优先；缓存未命中或过期回落 Redis；
     * Redis 也无值时返回 {@code defaultIfMissing}。
     */
    public boolean isEnabled(String switchKey, boolean defaultIfMissing) {
        CachedBool cached = localCache.get(switchKey);
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.value();
        }
        String payload = stringRedisTemplate.opsForValue().get(KEY_PREFIX + switchKey);
        boolean resolved = payload == null ? defaultIfMissing : "1".equals(payload);
        localCache.put(switchKey, new CachedBool(resolved, now.plus(LOCAL_TTL)));
        return resolved;
    }

    /**
     * 写入 Redis + 失效本地缓存。
     */
    public void write(TradingRiskSwitch riskSwitch) {
        stringRedisTemplate.opsForValue().set(
                KEY_PREFIX + riskSwitch.switchKey(),
                riskSwitch.enabled() ? "1" : "0"
        );
        localCache.remove(riskSwitch.switchKey());
    }

    public Optional<Boolean> peekLocal(String switchKey) {
        CachedBool cached = localCache.get(switchKey);
        if (cached == null || Instant.now().isAfter(cached.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(cached.value());
    }

    private record CachedBool(boolean value, Instant expiresAt) {
    }
}
