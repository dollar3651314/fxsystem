package com.falconx.market.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.MarketSwapRateSnapshot;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 隔夜利息费率共享快照的 Redis 实现。
 *
 * <p>该实现把 market owner 的费率历史规则写入 Redis，
 * 让 `trading-core-service` 能在不跨服务查库的前提下按结算日解析有效费率。
 *
 * <p>缓存语义：
 *
 * <ul>
 *   <li>TTL：`falconx.market.redis.swap-rate-ttl`，默认 `25h`</li>
 *   <li>刷新策略：启动预热 + `UTC 00:00` 定时全量刷新</li>
 *   <li>cache miss：返回空，由下游跳过本次结算并等待下一轮刷新</li>
 * </ul>
 */
@Repository
public class RedisMarketSwapRateSnapshotRepository implements MarketSwapRateSnapshotRepository {

    private static final String KEY_PREFIX = "falconx:market:swap-rate:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration swapRateTtl;

    public RedisMarketSwapRateSnapshotRepository(StringRedisTemplate stringRedisTemplate,
                                                 ObjectMapper objectMapper,
                                                 MarketServiceProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.swapRateTtl = properties.getRedis().getSwapRateTtl();
    }

    public void save(MarketSwapRateSnapshot snapshot) {
        try {
            stringRedisTemplate.opsForValue().set(
                    key(snapshot.symbol()),
                    objectMapper.writeValueAsString(snapshot),
                    swapRateTtl
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize market swap rate snapshot", exception);
        }
    }

    @Override
    public Optional<MarketSwapRateSnapshot> findBySymbol(String symbol) {
        String payload = stringRedisTemplate.opsForValue().get(key(symbol));
        if (payload == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, MarketSwapRateSnapshot.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to deserialize market swap rate snapshot", exception);
        }
    }

    private String key(String symbol) {
        return KEY_PREFIX + symbol;
    }
}
