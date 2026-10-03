package com.falconx.trading.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.falconx.trading.service.model.TradingSwapRateSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 隔夜利息共享快照的 Redis 读取实现。
 *
 * <p>该实现与 `market-service` 约定统一的 Redis key，
 * 让交易核心直接读取 owner 快照，不跨服务查库。
 *
 * <p>cache miss 策略固定为返回空，
 * 由上游跳过本次结算并在下一轮调度时重试。
 */
@Repository
public class RedisTradingSwapRateSnapshotRepository implements TradingSwapRateSnapshotRepository {

    private static final String KEY_PREFIX = "falconx:market:swap-rate:";
    private static final Duration TEST_SEED_TTL = Duration.ofHours(25);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisTradingSwapRateSnapshotRepository(StringRedisTemplate stringRedisTemplate,
                                                  ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<TradingSwapRateSnapshot> findBySymbol(String symbol) {
        String payload = stringRedisTemplate.opsForValue().get(key(symbol));
        if (payload == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, TradingSwapRateSnapshot.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to deserialize trading swap rate snapshot", exception);
        }
    }

    @Override
    public Map<String, TradingSwapRateSnapshot> findBySymbols(Collection<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyMap();
        }
        // 保留顺序，便于通过 index 对应 keys 与 payloads
        List<String> distinctSymbols = new ArrayList<>(new java.util.LinkedHashSet<>(symbols));
        List<String> keys = new ArrayList<>(distinctSymbols.size());
        for (String s : distinctSymbols) {
            keys.add(key(s));
        }
        // Redis MGET 单次往返拉所有 keys
        List<String> payloads = stringRedisTemplate.opsForValue().multiGet(keys);
        if (payloads == null) {
            return Collections.emptyMap();
        }
        Map<String, TradingSwapRateSnapshot> result = new LinkedHashMap<>(distinctSymbols.size());
        for (int i = 0; i < distinctSymbols.size(); i++) {
            String payload = i < payloads.size() ? payloads.get(i) : null;
            if (payload == null) continue;
            try {
                result.put(distinctSymbols.get(i), objectMapper.readValue(payload, TradingSwapRateSnapshot.class));
            } catch (JacksonException exception) {
                // 单条反序列化失败不影响其他 symbol；跳过即可，调用方会按缺失处理
                continue;
            }
        }
        return result;
    }

    /**
     * 仅供集成测试使用：向 Redis 写入隔夜利息共享快照种子数据。
     *
     * @param snapshot 测试快照
     */
    public void saveForTest(TradingSwapRateSnapshot snapshot) {
        try {
            stringRedisTemplate.opsForValue().set(
                    key(snapshot.symbol()),
                    objectMapper.writeValueAsString(snapshot),
                    TEST_SEED_TTL
            );
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize trading swap rate snapshot", exception);
        }
    }

    private String key(String symbol) {
        return KEY_PREFIX + symbol;
    }
}
