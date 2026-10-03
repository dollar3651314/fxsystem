package com.falconx.trading.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.falconx.trading.service.model.TradingScheduleSnapshot;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 交易时间快照仓储的 Redis 实现。
 *
 * <p>该实现通过与 `market-service` 约定一致的 Redis key 读取交易时间快照，
 * 避免 `trading-core-service` 为了交易时间校验去跨服务查库。
 *
 * <p>写入路径：生产环境由 `market-service` 独占写入（TTL 24h）；
 * {@link #saveForTest} 仅供集成测试种数据使用，不暴露到接口层。
 */
@Repository
public class RedisTradingScheduleSnapshotRepository implements TradingScheduleSnapshotRepository {

    private static final String KEY_PREFIX = "falconx:market:trading:schedule:";

    /** 测试种子写入使用的 TTL，略大于 market-service 的 24h，避免测试数据意外过期。 */
    private static final Duration TEST_SEED_TTL = Duration.ofHours(25);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisTradingScheduleSnapshotRepository(StringRedisTemplate stringRedisTemplate,
                                                  ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<TradingScheduleSnapshot> findBySymbol(String symbol) {
        String payload = stringRedisTemplate.opsForValue().get(key(symbol));
        if (payload == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, TradingScheduleSnapshot.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to deserialize trading schedule snapshot", exception);
        }
    }

    /**
     * 仅供集成测试使用：向 Redis 写入交易时间快照种子数据。
     *
     * <p>生产代码不应调用此方法；快照写入路径由 `market-service` 独占。
     *
     * @param snapshot 交易时间快照
     */
    public void saveForTest(TradingScheduleSnapshot snapshot) {
        try {
            stringRedisTemplate.opsForValue().set(
                    key(snapshot.symbol()),
                    objectMapper.writeValueAsString(snapshot),
                    TEST_SEED_TTL
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize trading schedule snapshot", exception);
        }
    }

    private String key(String symbol) {
        return KEY_PREFIX + symbol;
    }
}
