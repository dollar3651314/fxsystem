package com.falconx.market.service.impl;

import com.falconx.market.service.FxRateRedisCache;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 Redis 实现，仅供单元测试使用。
 *
 * <p>实现 {@link FxRateRedisCache}，支持 set/get/ttl，
 * 用于替换真实 Redis 连接，保证测试无 I/O 依赖。
 */
class FakeRedisCommands implements FxRateRedisCache {

    private final Map<String, String> data = new ConcurrentHashMap<>();
    private final Map<String, Long> ttls = new ConcurrentHashMap<>();

    @Override
    public void set(String key, String value, long ttlSeconds) {
        data.put(key, value);
        ttls.put(key, ttlSeconds);
    }

    @Override
    public String get(String key) {
        return data.get(key);
    }

    @Override
    public long ttl(String key) {
        return ttls.getOrDefault(key, 0L);
    }
}
