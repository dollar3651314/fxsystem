package com.falconx.market.repository;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.market.repository.mapper.MarketSymbolAdminMapper;
import com.falconx.market.repository.mapper.record.MarketSymbolAdminRecord;
import com.falconx.market.repository.mapper.record.MarketSymbolQuoteMappingAdminRecord;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * SymbolSpec 共享快照 Redis 实现。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.1.B：把 mapping 完整交易参数与系统级
 * precision 写入 Redis Hash 供 trading-core 高频读。
 *
 * <p>缓存语义：
 * <ul>
 *   <li>Key：{@code falconx:market:symbol-spec:{platformSymbol}}</li>
 *   <li>TTL：无（永久；mapping CRUD afterCommit 与启动 warmup 覆写）</li>
 *   <li>cache miss：返回 empty，trading-core 拒单 {@code SYMBOL_SPEC_NOT_FOUND}</li>
 * </ul>
 */
@Repository
public class RedisMarketSymbolSpecRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisMarketSymbolSpecRepository.class);

    private static final String KEY_PREFIX = "falconx:market:symbol-spec:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final MarketSymbolAdminMapper symbolMapper;

    public RedisMarketSymbolSpecRepository(StringRedisTemplate stringRedisTemplate,
                                           ObjectMapper objectMapper,
                                           MarketSymbolAdminMapper symbolMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.symbolMapper = symbolMapper;
    }

    public void save(SymbolSpec spec) {
        try {
            stringRedisTemplate.opsForValue().set(key(spec.platformSymbol()),
                    objectMapper.writeValueAsString(spec));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize symbol spec", exception);
        }
    }

    public Optional<SymbolSpec> findByPlatformSymbol(String platformSymbol) {
        String payload = stringRedisTemplate.opsForValue().get(key(platformSymbol));
        if (payload == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, SymbolSpec.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to deserialize symbol spec", exception);
        }
    }

    public void delete(String platformSymbol) {
        stringRedisTemplate.delete(key(platformSymbol));
    }

    /**
     * 从 mapping 装配 SymbolSpec。V15 起 precision 是 mapping 上的系统级必填配置；
     * STAGE-14B Task 5 起 base/quote currency 取自 {@code source}（{@code t_symbol}），
     * 写入快照供 trading-core 算法层识别每个 symbol 的计价币与基础币。
     * STAGE-14C2 Task 1 起 category 同样取自 {@code source}（{@code t_symbol.category}），
     * 供 trading-core 按品种类目控制 FX_PAUSED 行为；source 缺失时 category 置 null。
     */
    public SymbolSpec toSpec(MarketSymbolQuoteMappingAdminRecord mapping, MarketSymbolAdminRecord source) {
        if (source == null) {
            // mapping 引用的 LP source symbol 已从 t_symbol 删除：base/quote 缺位但不应中断 warmup，置 null 并告警
            log.warn("market.symbol-spec.source-missing platformSymbol={} sourceLpCode={} sourceSymbol={}",
                    mapping.platformSymbol(), mapping.sourceLpCode(), mapping.sourceSymbol());
        }
        return new SymbolSpec(
                mapping.platformSymbol(),
                mapping.maxLeverage(),
                mapping.takerFeeRate(),
                mapping.spread(),
                mapping.minQty(),
                mapping.maxQty(),
                mapping.minNotional(),
                mapping.pricePrecision(),
                mapping.qtyPrecision(),
                source != null ? source.baseCurrency() : null,
                source != null ? source.quoteCurrency() : null,
                source != null ? source.category() : null
        );
    }

    private String key(String platformSymbol) {
        return KEY_PREFIX + platformSymbol;
    }
}
