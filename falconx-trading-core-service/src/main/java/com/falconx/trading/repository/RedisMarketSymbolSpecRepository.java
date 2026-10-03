package com.falconx.trading.repository;

import com.falconx.market.contract.SymbolSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Trading-core 侧 SymbolSpec Redis 读 + 10 秒本地缓存。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.2：本仓储是 trading-core 开仓 / 平仓 / 强平 / Swap
 * 链路读取 platform symbol 交易参数的唯一通道，绝不跨 schema 直查 {@code falconx_market}。
 *
 * <p>缓存策略：本地 ConcurrentHashMap 10 秒 TTL；mapping 更新最迟 10 秒生效。
 * 缺失返回 {@link Optional#empty()}，service 层在 SYMBOL_SPEC_NOT_FOUND 拒单。
 */
@Repository
public class RedisMarketSymbolSpecRepository implements MarketSymbolSpecRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisMarketSymbolSpecRepository.class);
    private static final String KEY_PREFIX = "falconx:market:symbol-spec:";
    private static final Duration LOCAL_CACHE_TTL = Duration.ofSeconds(10);
    /** 容量上限：交易对随业务增长（新合约 / 重命名）数量上升，超过即触发清扫过期项。 */
    private static final int MAX_LOCAL_CACHE_SIZE = 10_000;
    private static final int EVICT_THRESHOLD = 8_000;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CachedSpec> localCache = new ConcurrentHashMap<>();

    public RedisMarketSymbolSpecRepository(StringRedisTemplate stringRedisTemplate,
                                           ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<SymbolSpec> findByPlatformSymbol(String platformSymbol) {
        if (platformSymbol == null || platformSymbol.isBlank()) {
            return Optional.empty();
        }
        CachedSpec cached = localCache.get(platformSymbol);
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return Optional.ofNullable(cached.spec());
        }
        Optional<SymbolSpec> fresh = readFromRedis(platformSymbol);
        // 容量保护（2026-05-20 加固）：无主动清理时，过期 entry 占用堆内存，长寿命进程持续累积。
        // 超过 EVICT_THRESHOLD 时清扫所有过期项；仍超 MAX 时清最老 entry 兜底。
        if (localCache.size() >= EVICT_THRESHOLD) {
            evictExpired(now);
            if (localCache.size() >= MAX_LOCAL_CACHE_SIZE) {
                evictOldestForce(MAX_LOCAL_CACHE_SIZE / 4);
            }
        }
        localCache.put(platformSymbol, new CachedSpec(fresh.orElse(null), now.plus(LOCAL_CACHE_TTL)));
        return fresh;
    }

    private void evictExpired(Instant now) {
        localCache.entrySet().removeIf(e -> !now.isBefore(e.getValue().expiresAt()));
    }

    /** 强制清除最早过期的若干 entry，防止极端场景下（连续 put 速度 > evict 速度）继续涨。 */
    private void evictOldestForce(int howMany) {
        localCache.entrySet().stream()
                .sorted((a, b) -> a.getValue().expiresAt().compareTo(b.getValue().expiresAt()))
                .limit(howMany)
                .forEach(e -> localCache.remove(e.getKey()));
    }

    private Optional<SymbolSpec> readFromRedis(String platformSymbol) {
        String payload = stringRedisTemplate.opsForValue().get(KEY_PREFIX + platformSymbol);
        if (payload == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, SymbolSpec.class));
        } catch (JacksonException exception) {
            log.warn("trading.symbol-spec.deserialize.failed platformSymbol={} reason={}",
                    platformSymbol, exception.getMessage());
            return Optional.empty();
        }
    }

    private record CachedSpec(SymbolSpec spec, Instant expiresAt) {
    }
}
