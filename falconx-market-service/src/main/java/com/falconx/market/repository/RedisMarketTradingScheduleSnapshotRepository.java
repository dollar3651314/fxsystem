package com.falconx.market.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.MarketTradingScheduleSnapshot;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 交易时间快照仓储的 Redis 实现。
 *
 * <p>该实现把市场元数据聚合结果写入 Redis，
 * 让 `trading-core-service` 不需要跨服务读取 `falconx_market`。
 *
 * <p>接口层只暴露查询能力；写入能力保留在该实现类内部，
 * 供 `market-service` 自己的预热任务使用，避免其他调用方误把它当成通用写接口。
 */
@Repository
public class RedisMarketTradingScheduleSnapshotRepository implements MarketTradingScheduleSnapshotRepository {

    private static final String KEY_PREFIX = "falconx:market:trading:schedule:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration tradingScheduleTtl;

    public RedisMarketTradingScheduleSnapshotRepository(StringRedisTemplate stringRedisTemplate,
                                                        ObjectMapper objectMapper,
                                                        MarketServiceProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.tradingScheduleTtl = properties.getRedis().getTradingScheduleTtl();
    }

    public void save(MarketTradingScheduleSnapshot snapshot) {
        try {
            stringRedisTemplate.opsForValue().set(
                    key(snapshot.symbol()),
                    objectMapper.writeValueAsString(snapshot),
                    tradingScheduleTtl
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize market trading schedule snapshot", exception);
        }
    }

    @Override
    public Optional<MarketTradingScheduleSnapshot> findBySymbol(String symbol) {
        String payload = stringRedisTemplate.opsForValue().get(key(symbol));
        if (payload == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, MarketTradingScheduleSnapshot.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to deserialize market trading schedule snapshot", exception);
        }
    }

    private String key(String symbol) {
        return KEY_PREFIX + symbol;
    }
}
