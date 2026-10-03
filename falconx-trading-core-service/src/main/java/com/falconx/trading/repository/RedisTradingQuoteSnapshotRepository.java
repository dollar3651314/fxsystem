package com.falconx.trading.repository;

import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.entity.TradingQuoteQualityStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 交易内部最新价格快照 Repository 的 Redis 实现。
 *
 * <p>该实现负责把高频价格快照落到 Redis，
 * 让同步下单和报价驱动引擎共享统一的最新标记价视图。
 *
 * <p>缓存语义：
 *
 * <ul>
 *   <li>TTL：`falconx.trading.cache.quote-ttl`，默认 `10s`</li>
 *   <li>刷新策略：每条 `market.price.tick` 写入时刷新 key 过期时间</li>
 *   <li>cache miss：返回 `Optional.empty()`，由上游按缺价/拒单路径处理</li>
 * </ul>
 */
@Repository
public class RedisTradingQuoteSnapshotRepository implements TradingQuoteSnapshotRepository {

    private final StringRedisTemplate stringRedisTemplate;
    private final Duration maxQuoteAge;
    private final Duration quoteTtl;

    public RedisTradingQuoteSnapshotRepository(StringRedisTemplate stringRedisTemplate,
                                              TradingCoreServiceProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.maxQuoteAge = properties.getStale().getMaxAge();
        this.quoteTtl = properties.getCache().getQuoteTtl();
    }

    @Override
    public TradingQuoteSnapshot save(TradingQuoteSnapshot snapshot) {
        // 2026-05-26 Sprint 4 perf：原 8 次 hash put + 1 expire = 9 RT，
        // 改用 putAll(HMSET 1 RT) + expire + 可选 delete = 2-3 RT。
        String key = key(snapshot.symbol());
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>(8);
        fields.put("bid", snapshot.bid().toPlainString());
        fields.put("ask", snapshot.ask().toPlainString());
        fields.put("mark", snapshot.mark().toPlainString());
        fields.put("ts", String.valueOf(snapshot.ts().toInstant().toEpochMilli()));
        fields.put("source", snapshot.source());
        fields.put("stale", String.valueOf(snapshot.stale()));
        fields.put("quoteStatus", snapshot.qualityStatus().name());
        if (snapshot.qualityReason() != null) {
            fields.put("qualityReason", snapshot.qualityReason());
        }
        stringRedisTemplate.opsForHash().putAll(key, fields);
        if (snapshot.qualityReason() == null) {
            stringRedisTemplate.opsForHash().delete(key, "qualityReason");
        }
        stringRedisTemplate.expire(key, quoteTtl);
        return toEffectiveSnapshot(
                snapshot.symbol(),
                snapshot.bid(),
                snapshot.ask(),
                snapshot.mark(),
                snapshot.ts().toInstant().toEpochMilli(),
                snapshot.source(),
                snapshot.qualityStatus().name(),
                snapshot.qualityReason()
        );
    }

    @Override
    public Optional<TradingQuoteSnapshot> findBySymbol(String symbol) {
        Map<Object, Object> values = stringRedisTemplate.opsForHash().entries(key(symbol));
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toEffectiveSnapshot(
                symbol,
                new BigDecimal((String) values.get("bid")),
                new BigDecimal((String) values.get("ask")),
                new BigDecimal((String) values.get("mark")),
                Long.parseLong((String) values.get("ts")),
                (String) values.get("source"),
                (String) values.get("quoteStatus"),
                (String) values.get("qualityReason")
        ));
    }

    private TradingQuoteSnapshot toEffectiveSnapshot(String symbol,
                                                     BigDecimal bid,
                                                     BigDecimal ask,
                                                     BigDecimal mark,
                                                     long tsEpochMillis,
                                                     String source,
                                                     String quoteStatus,
                                                     String qualityReason) {
        OffsetDateTime quoteTimestamp = OffsetDateTime.ofInstant(Instant.ofEpochMilli(tsEpochMillis), ZoneOffset.UTC);
        boolean timeStale = Duration.between(quoteTimestamp, OffsetDateTime.now(ZoneOffset.UTC))
                .abs()
                .compareTo(maxQuoteAge) > 0;
        TradingQuoteQualityStatus storedStatus = TradingQuoteQualityStatus.parse(quoteStatus, timeStale);
        TradingQuoteQualityStatus effectiveStatus = timeStale && storedStatus == TradingQuoteQualityStatus.FRESH
                ? TradingQuoteQualityStatus.STALE
                : storedStatus;
        return new TradingQuoteSnapshot(
                symbol,
                bid,
                ask,
                mark,
                quoteTimestamp,
                source,
                effectiveStatus.stale(),
                effectiveStatus,
                qualityReason
        );
    }

    @Override
    public Map<String, TradingQuoteSnapshot> findBySymbols(Collection<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }
        List<String> ordered = new ArrayList<>(symbols);
        List<Object> rawResults = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String symbol : ordered) {
                connection.hashCommands().hGetAll(key(symbol).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        Map<String, TradingQuoteSnapshot> out = new LinkedHashMap<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            String symbol = ordered.get(i);
            Object raw = i < rawResults.size() ? rawResults.get(i) : null;
            if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
                continue;
            }
            Map<String, String> fields = new HashMap<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String k = entry.getKey() == null ? null : entry.getKey().toString();
                String v = entry.getValue() == null ? null : entry.getValue().toString();
                if (k != null && v != null) {
                    fields.put(k, v);
                }
            }
            String bidStr = fields.get("bid");
            String askStr = fields.get("ask");
            String markStr = fields.get("mark");
            String tsStr = fields.get("ts");
            if (bidStr == null || askStr == null || markStr == null || tsStr == null) {
                continue;
            }
            out.put(symbol, toEffectiveSnapshot(
                    symbol,
                    new BigDecimal(bidStr),
                    new BigDecimal(askStr),
                    new BigDecimal(markStr),
                    Long.parseLong(tsStr),
                    fields.get("source"),
                    fields.get("quoteStatus"),
                    fields.get("qualityReason")
            ));
        }
        return out;
    }

    private String key(String symbol) {
        return "falconx:trading:quote:snapshot:" + symbol;
    }
}
