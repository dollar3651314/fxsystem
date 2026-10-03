package com.falconx.market.repository;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.MarketQuoteQualityStatus;
import com.falconx.market.entity.StandardQuote;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis 展示用最后有效参考价仓储。
 *
 * <p>缓存语义：
 *
 * <ul>
 *   <li>TTL：`falconx.market.redis.reference-quote-ttl`，默认 `30d`</li>
 *   <li>刷新策略：每条 fresh 标准报价写入实时价时同步刷新</li>
 *   <li>cache miss：回查 ClickHouse 最新 tick，命中后回填 Redis；仍未命中则返回空</li>
 * </ul>
 */
@Repository
@Profile("!stub")
public class RedisMarketReferenceQuoteRepository implements MarketReferenceQuoteRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisMarketReferenceQuoteRepository.class);
    private static final String KEY_PREFIX = "falconx:market:last-valid-price:";

    private final StringRedisTemplate stringRedisTemplate;
    private final MarketQuoteHistoryRepository marketQuoteHistoryRepository;
    private final Duration referenceQuoteTtl;

    public RedisMarketReferenceQuoteRepository(StringRedisTemplate stringRedisTemplate,
                                               MarketQuoteHistoryRepository marketQuoteHistoryRepository,
                                               MarketServiceProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.marketQuoteHistoryRepository = marketQuoteHistoryRepository;
        this.referenceQuoteTtl = properties.getRedis().getReferenceQuoteTtl();
    }

    @Override
    public void saveLastValid(StandardQuote quote) {
        // 2026-05-26 Sprint 4 perf：原 8 次 hash put + 1 expire = 9 RT，
        // 改用 putAll(HMSET 1 RT) + expire + 可选 delete = 2-3 RT。
        String key = key(quote.symbol());
        Map<String, String> fields = new LinkedHashMap<>(8);
        fields.put("bid", quote.bid().toPlainString());
        fields.put("ask", quote.ask().toPlainString());
        fields.put("mid", quote.mid().toPlainString());
        fields.put("mark", quote.mark().toPlainString());
        fields.put("ts", String.valueOf(quote.ts().toInstant().toEpochMilli()));
        fields.put("source", quote.source());
        fields.put("quoteStatus", quote.qualityStatus().name());
        if (quote.qualityReason() != null) {
            fields.put("qualityReason", quote.qualityReason());
        }
        stringRedisTemplate.opsForHash().putAll(key, fields);
        if (quote.qualityReason() == null) {
            stringRedisTemplate.opsForHash().delete(key, "qualityReason");
        }
        stringRedisTemplate.expire(key, referenceQuoteTtl);
    }

    @Override
    public Optional<StandardQuote> findBySymbol(String symbol) {
        Optional<StandardQuote> cached = readFromRedis(symbol);
        if (cached.isPresent()) {
            return cached;
        }
        try {
            Optional<StandardQuote> historicalQuote = marketQuoteHistoryRepository.findLatestBySymbol(symbol);
            historicalQuote.ifPresent(this::saveLastValid);
            return historicalQuote;
        } catch (RuntimeException exception) {
            log.warn("market.reference-quote.history-fallback.failed symbol={} reason={}",
                    symbol,
                    exception.toString());
            return Optional.empty();
        }
    }

    private Optional<StandardQuote> readFromRedis(String symbol) {
        Map<Object, Object> values = stringRedisTemplate.opsForHash().entries(key(symbol));
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        OffsetDateTime quoteTimestamp = OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(Long.parseLong((String) values.get("ts"))),
                ZoneOffset.UTC
        );
        return Optional.of(new StandardQuote(
                symbol,
                new BigDecimal((String) values.get("bid")),
                new BigDecimal((String) values.get("ask")),
                new BigDecimal((String) values.get("mid")),
                new BigDecimal((String) values.get("mark")),
                quoteTimestamp,
                (String) values.get("source"),
                true,
                MarketQuoteQualityStatus.STALE,
                (String) values.get("qualityReason")
        ));
    }

    @Override
    public Map<String, StandardQuote> findBySymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }
        List<Object> rawResults = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (String symbol : symbols) {
                connection.hashCommands().hGetAll(key(symbol).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        Map<String, StandardQuote> out = new LinkedHashMap<>(symbols.size());
        for (int i = 0; i < symbols.size(); i++) {
            String symbol = symbols.get(i);
            Object raw = i < rawResults.size() ? rawResults.get(i) : null;
            if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
                continue;
            }
            parsePipelineHash(symbol, map).ifPresent(q -> out.put(symbol, q));
        }
        return out;
    }

    private Optional<StandardQuote> parsePipelineHash(String symbol, Map<?, ?> raw) {
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
            return Optional.of(new StandardQuote(
                    symbol,
                    new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(mid), new BigDecimal(mark),
                    quoteTs, source,
                    true, MarketQuoteQualityStatus.STALE, fields.get("qualityReason")
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
        return KEY_PREFIX + symbol;
    }
}
