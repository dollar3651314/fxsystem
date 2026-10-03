package com.falconx.trading.service.impl;

import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.entity.SymbolLeverageTier;
import com.falconx.trading.repository.SymbolLeverageTierRepository;
import com.falconx.trading.service.LeverageTierResolver;
import com.falconx.trading.service.model.LeverageTier;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * {@link LeverageTierResolver} 默认实现：按 notional 落档 + 本地短 TTL 缓存。
 *
 * <p>缓存策略参考 {@code RedisMarketSymbolSpecRepository}：本地 {@link ConcurrentHashMap}，
 * key = {@code symbol|groupCode}，value 携带过期时间戳；超容量时清扫过期项再兜底清最老项。
 *
 * <p>缓存三要素（AGENTS §3.9）：
 * <ul>
 *   <li>TTL：{@code falconx.trading.tier.cache-refresh-seconds}（默认 30s）。</li>
 *   <li>刷新：惰性过期重查（读到过期 entry 即回源 DB 重填）。</li>
 *   <li>降级：cache miss 查 DB；DB 查空缓存空列表并返回 {@link Optional#empty()}
 *       （caller 判 empty → 30072 TIER_CONFIG_NOT_FOUND）。</li>
 * </ul>
 *
 * <p>落档规则：按 {@code [notionalLower, notionalUpper)} 区间命中（下界含、上界不含）；
 * 最高档 {@code notionalUpper=null} 表示无上限，{@code notional >= lower} 即落入。
 * 档位列表已由 Repository 按 notional_lower 升序、含 default 组回退，Resolver 不重复处理。
 */
@Service
public class DefaultLeverageTierResolver implements LeverageTierResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultLeverageTierResolver.class);

    /** 容量上限：缓存 key = symbol×group，数量随业务增长，超过即触发清扫。 */
    private static final int MAX_LOCAL_CACHE_SIZE = 10_000;
    private static final int EVICT_THRESHOLD = 8_000;

    private final SymbolLeverageTierRepository repository;
    private final Duration cacheTtl;
    /** 时间源；生产用 {@code Instant::now}，测试可注入可控时钟验证惰性过期。 */
    private final Supplier<Instant> clock;
    private final ConcurrentHashMap<String, CachedTiers> localCache = new ConcurrentHashMap<>();

    // STAGE-14C1 Task 6：本类有两个等元数（arity 不同但同为构造注入候选）构造器，
    // Spring 无法自动选择会报 “No default constructor found”。显式标注生产构造器为注入入口。
    @Autowired
    public DefaultLeverageTierResolver(SymbolLeverageTierRepository repository,
                                       TradingCoreServiceProperties properties) {
        this(repository, Duration.ofSeconds(properties.getTier().getCacheRefreshSeconds()), Instant::now);
    }

    /** 测试用构造：注入可控 TTL 与时钟。 */
    DefaultLeverageTierResolver(SymbolLeverageTierRepository repository,
                                Duration cacheTtl,
                                Supplier<Instant> clock) {
        this.repository = repository;
        this.cacheTtl = cacheTtl;
        this.clock = clock;
    }

    @Override
    public Optional<LeverageTier> resolve(String symbol, BigDecimal notionalInAccount, String groupCode) {
        if (symbol == null || notionalInAccount == null || groupCode == null) {
            return Optional.empty();
        }
        List<SymbolLeverageTier> tiers = loadTiers(symbol, groupCode);
        if (tiers.isEmpty()) {
            // 降级：无配置 → empty，由 caller 判 30072
            return Optional.empty();
        }
        return matchTier(tiers, notionalInAccount);
    }

    /** 取该 (symbol, groupCode) 的档位列表，本地 TTL 缓存 + 惰性过期重查。 */
    private List<SymbolLeverageTier> loadTiers(String symbol, String groupCode) {
        String key = symbol + "|" + groupCode;
        Instant now = clock.get();
        CachedTiers cached = localCache.get(key);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.tiers();
        }
        List<SymbolLeverageTier> fresh = repository.findTiers(symbol, groupCode);
        // 容量保护（参考 RedisMarketSymbolSpecRepository）：先清扫过期项，仍超上限再强制清最老项。
        if (localCache.size() >= EVICT_THRESHOLD) {
            evictExpired(now);
            if (localCache.size() >= MAX_LOCAL_CACHE_SIZE) {
                evictOldestForce(MAX_LOCAL_CACHE_SIZE / 4);
            }
        }
        localCache.put(key, new CachedTiers(fresh, now.plus(cacheTtl)));
        return fresh;
    }

    /** 按 notional 落入 {@code [lower, upper)} 区间（lower 含、upper 不含；upper=null 无上限）。 */
    private Optional<LeverageTier> matchTier(List<SymbolLeverageTier> tiers, BigDecimal notional) {
        for (SymbolLeverageTier t : tiers) {
            boolean aboveLower = notional.compareTo(t.notionalLower()) >= 0;
            boolean belowUpper = t.notionalUpper() == null || notional.compareTo(t.notionalUpper()) < 0;
            if (aboveLower && belowUpper) {
                return Optional.of(new LeverageTier(
                        t.tierNo(), t.maxLeverage(), t.mmRate(), t.notionalLower(), t.notionalUpper()));
            }
        }
        // 理论上 tier1 lower=0 应兜住所有非负 notional；落不进任何档说明配置缺口。
        log.warn("trading.tier.no-match notional={} tierCount={}", notional, tiers.size());
        return Optional.empty();
    }

    @Override
    public void invalidate(String symbol, String groupCode) {
        if (symbol == null || groupCode == null) {
            return;
        }
        // 缓存 key 与 loadTiers 一致：symbol|groupCode。仅清本机；多实例其余靠 30s 惰性过期兜底。
        String key = symbol + "|" + groupCode;
        CachedTiers removed = localCache.remove(key);
        if (removed != null) {
            log.info("trading.tier.cache.invalidated symbol={} groupCode={}", symbol, groupCode);
        }
    }

    private void evictExpired(Instant now) {
        localCache.entrySet().removeIf(e -> !now.isBefore(e.getValue().expiresAt()));
    }

    private void evictOldestForce(int howMany) {
        localCache.entrySet().stream()
                .sorted((a, b) -> a.getValue().expiresAt().compareTo(b.getValue().expiresAt()))
                .limit(howMany)
                .forEach(e -> localCache.remove(e.getKey()));
    }

    private record CachedTiers(List<SymbolLeverageTier> tiers, Instant expiresAt) {
    }
}
