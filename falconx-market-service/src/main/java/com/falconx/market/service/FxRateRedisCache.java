package com.falconx.market.service;

/**
 * FX 汇率 Redis 缓存操作抽象。
 *
 * <p>通过接口解耦底层 Redis 客户端实现，使 {@link com.falconx.market.service.impl.DefaultFxRateService}
 * 在单元测试中可以注入 FakeRedisCommands，生产环境注入基于 {@code StringRedisTemplate} 的真实实现。
 */
public interface FxRateRedisCache {

    /**
     * 写入键值并设置 TTL（秒）。
     */
    void set(String key, String value, long ttlSeconds);

    /**
     * 读取键值，不存在时返回 null。
     */
    String get(String key);

    /**
     * 查询某 key 的剩余 TTL 秒数。
     *
     * <p><b>预留</b>：当前生产代码未读取此值。STAGE-14A Task 8 的 FxRateStaleDetector
     * 用于辅助 stale 判定的备用通道（与内存 latestByPair.eventTimeMillis 互校）。
     *
     * @return 剩余秒数，key 不存在返回 0
     */
    long ttl(String key);
}
