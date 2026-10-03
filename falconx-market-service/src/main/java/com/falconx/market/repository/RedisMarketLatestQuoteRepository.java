package com.falconx.market.repository;

import com.falconx.market.entity.StandardQuote;
import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.MarketQuoteQualityStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 最新报价 Redis 读模型实现。
 *
 * <p>该实现把 market-service 对北向查询暴露的“最新价读模型”切换到 Redis，
 * 与数据库设计里“最新价格以 Redis 为准”的规则保持一致。
 *
 * <p>缓存语义：
 *
 * <ul>
 *   <li>TTL：`falconx.market.redis.quote-ttl`，默认 `10s`</li>
 *   <li>刷新策略：每条标准报价写入 Redis 时刷新 key 过期时间</li>
 *   <li>cache miss：返回 `Optional.empty()`，由北向查询按缺价路径处理</li>
 * </ul>
 */
@Repository
@Profile("!stub")
public class RedisMarketLatestQuoteRepository implements MarketLatestQuoteRepository {

    private final StringRedisTemplate stringRedisTemplate;
    private final Duration maxQuoteAge;
    private final Duration quoteTtl;

    public RedisMarketLatestQuoteRepository(StringRedisTemplate stringRedisTemplate,
                                            MarketServiceProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.maxQuoteAge = properties.getStale().getMaxAge();
        this.quoteTtl = properties.getRedis().getQuoteTtl();
    }

    @Override
    public void save(StandardQuote quote) {
        // 2026-05-26 Sprint 4 perf：原 9 次 hash put + 1 expire = 10 RT，
        // 改用 putAll(HMSET 1 RT) + expire + 可选 delete = 2-3 RT。
        String key = key(quote.symbol());
        Map<String, String> fields = new LinkedHashMap<>(9);
        fields.put("bid", quote.bid().toPlainString());
        fields.put("ask", quote.ask().toPlainString());
        fields.put("mid", quote.mid().toPlainString());
        fields.put("mark", quote.mark().toPlainString());
        fields.put("ts", String.valueOf(quote.ts().toInstant().toEpochMilli()));
        fields.put("source", quote.source());
        fields.put("stale", String.valueOf(quote.stale()));
        fields.put("quoteStatus", quote.qualityStatus().name());
        if (quote.qualityReason() != null) {
            fields.put("qualityReason", quote.qualityReason());
        }
        stringRedisTemplate.opsForHash().putAll(key, fields);
        if (quote.qualityReason() == null) {
            stringRedisTemplate.opsForHash().delete(key, "qualityReason");
        }
        stringRedisTemplate.expire(key, quoteTtl);
    }

    @Override
    public Optional<StandardQuote> findBySymbol(String symbol) {
        Map<Object, Object> values = stringRedisTemplate.opsForHash().entries(key(symbol));
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        String bid = stringValue(values, "bid");
        String ask = stringValue(values, "ask");
        String mid = stringValue(values, "mid");
        String mark = stringValue(values, "mark");
        String ts = stringValue(values, "ts");
        String source = stringValue(values, "source");
        if (bid == null || ask == null || mid == null || mark == null || ts == null || source == null) {
            return Optional.empty();
        }
        try {
            OffsetDateTime quoteTimestamp = OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(Long.parseLong(ts)),
                    ZoneOffset.UTC
            );
            boolean timeStale = Duration.between(quoteTimestamp, OffsetDateTime.now(ZoneOffset.UTC))
                    .abs()
                    .compareTo(maxQuoteAge) > 0;
            MarketQuoteQualityStatus storedStatus = parseQualityStatus(stringValue(values, "quoteStatus"), timeStale);
            MarketQuoteQualityStatus effectiveStatus = timeStale && storedStatus == MarketQuoteQualityStatus.FRESH
                    ? MarketQuoteQualityStatus.STALE
                    : storedStatus;
            return Optional.of(new StandardQuote(
                    symbol,
                    new BigDecimal(bid),
                    new BigDecimal(ask),
                    new BigDecimal(mid),
                    new BigDecimal(mark),
                    quoteTimestamp,
                    source,
                    effectiveStatus.stale(),
                    effectiveStatus,
                    stringValue(values, "qualityReason")
            ));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Map<String, StandardQuote> findBySymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }
        // Pipeline 批量 HGETALL：1 RTT 返回 N 个 Hash，避开 1571 次串行 Redis 调用
        List<Object> rawResults = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String symbol : symbols) {
                connection.hashCommands().hGetAll(key(symbol).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        Map<String, StandardQuote> out = new LinkedHashMap<>(symbols.size());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (int i = 0; i < symbols.size(); i++) {
            String symbol = symbols.get(i);
            Object raw = i < rawResults.size() ? rawResults.get(i) : null;
            if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
                continue;
            }
            parseHash(symbol, map, now).ifPresent(q -> out.put(symbol, q));
        }
        return out;
    }

    private Optional<StandardQuote> parseHash(String symbol, Map<?, ?> raw, OffsetDateTime now) {
        Map<String, String> fields = new HashMap<>(raw.size());
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String k = stringify(entry.getKey());
            String v = stringify(entry.getValue());
            if (k != null && v != null) {
                fields.put(k, v);
            }
        }
        String bid = fields.get("bid");
        String ask = fields.get("ask");
        String mid = fields.get("mid");
        String mark = fields.get("mark");
        String ts = fields.get("ts");
        String source = fields.get("source");
        if (bid == null || ask == null || mid == null || mark == null || ts == null || source == null) {
            return Optional.empty();
        }
        try {
            OffsetDateTime quoteTs = OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(Long.parseLong(ts)), ZoneOffset.UTC);
            boolean timeStale = Duration.between(quoteTs, now).abs().compareTo(maxQuoteAge) > 0;
            MarketQuoteQualityStatus storedStatus = parseQualityStatus(fields.get("quoteStatus"), timeStale);
            MarketQuoteQualityStatus effective = timeStale && storedStatus == MarketQuoteQualityStatus.FRESH
                    ? MarketQuoteQualityStatus.STALE
                    : storedStatus;
            return Optional.of(new StandardQuote(
                    symbol,
                    new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(mid), new BigDecimal(mark),
                    quoteTs, source,
                    effective.stale(), effective, fields.get("qualityReason")
            ));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static String stringify(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s.isBlank() ? null : s;
        if (value instanceof byte[] bytes) {
            String s = new String(bytes, StandardCharsets.UTF_8);
            return s.isBlank() ? null : s;
        }
        String s = value.toString();
        return s.isBlank() ? null : s;
    }

    private String key(String symbol) {
        return "falconx:market:price:" + symbol;
    }

    private String stringValue(Map<Object, Object> values, String fieldName) {
        Object value = values.get(fieldName);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private MarketQuoteQualityStatus parseQualityStatus(String value, boolean timeStale) {
        if (value == null) {
            return timeStale ? MarketQuoteQualityStatus.STALE : MarketQuoteQualityStatus.FRESH;
        }
        try {
            return MarketQuoteQualityStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return MarketQuoteQualityStatus.STALE;
        }
    }
}
