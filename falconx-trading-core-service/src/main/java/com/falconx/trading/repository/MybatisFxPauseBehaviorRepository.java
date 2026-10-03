package com.falconx.trading.repository;

import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.entity.FxPauseBehavior;
import com.falconx.trading.repository.mapper.FxPauseBehaviorMapper;
import com.falconx.trading.repository.mapper.record.FxPauseBehaviorRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * {@link FxPauseBehaviorRepository} 的 MyBatis 实现（读 + admin 写 + 本地全量 TTL 缓存）。
 *
 * <p>读/写路径均通过 owner（trading）真实 Mapper + XML 完成，符合 AGENTS §3.2.4。
 * STAGE-14D3a Task 6 加 {@link #updateByCategory}：写库后失效本地快照（{@code snapshot.set(null)}），
 * 下次读整表回源重填 → console 改后即时生效（不等 TTL）。
 *
 * <p>缓存策略：{@code t_fx_pause_behavior} 仅 8 行小数据，整表全量 {@code selectAll} 一次性
 * 加载到 {@code Map<category, FxPauseBehavior>} 快照（参考 {@code DefaultLeverageTierResolver}
 * 的本地 TTL + 惰性过期模式）；{@code findByCategory}/{@code findAll} 均走该快照，不逐行查 DB。
 *
 * <p>缓存三要素（AGENTS §3.9）：
 * <ul>
 *   <li>TTL：{@code falconx.trading.fx-pause.cache-refresh-seconds}（默认 60s）。</li>
 *   <li>刷新：惰性过期重查（读到过期快照即整表回源重填；console 改后最长 60s 内生效）。</li>
 *   <li>降级：缓存命中后快照内无该 category → {@link Optional#empty()}
 *       （caller Task 6 判 empty → 降级 allow_all，不阻断）。</li>
 * </ul>
 */
@Repository
public class MybatisFxPauseBehaviorRepository implements FxPauseBehaviorRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisFxPauseBehaviorRepository.class);

    private final FxPauseBehaviorMapper fxPauseBehaviorMapper;
    private final Duration cacheTtl;
    /** 时间源；生产用 {@code Instant::now}，测试可注入可控时钟验证惰性过期。 */
    private final Supplier<Instant> clock;
    /** 全量快照（category → behavior）+ 过期时间戳，整体原子替换。 */
    private final AtomicReference<CachedSnapshot> snapshot = new AtomicReference<>();

    // Spring 注入入口（生产构造器）。本类含测试用多元构造器，显式标注避免选择歧义。
    @Autowired
    public MybatisFxPauseBehaviorRepository(FxPauseBehaviorMapper fxPauseBehaviorMapper,
                                            TradingCoreServiceProperties properties) {
        this(fxPauseBehaviorMapper,
                Duration.ofSeconds(properties.getFxPause().getCacheRefreshSeconds()),
                Instant::now);
    }

    /** 测试用构造：注入可控 TTL 与时钟。 */
    MybatisFxPauseBehaviorRepository(FxPauseBehaviorMapper fxPauseBehaviorMapper,
                                     Duration cacheTtl,
                                     Supplier<Instant> clock) {
        this.fxPauseBehaviorMapper = fxPauseBehaviorMapper;
        this.cacheTtl = cacheTtl;
        this.clock = clock;
    }

    @Override
    public Optional<FxPauseBehavior> findByCategory(int category) {
        // 缓存命中后快照内无该 category → empty（caller Task 6 降级 allow_all）
        return Optional.ofNullable(loadSnapshot().byCategory().get(category));
    }

    @Override
    public List<FxPauseBehavior> findAll() {
        return loadSnapshot().ordered();
    }

    @Override
    public void updateByCategory(int category, boolean allowOpen, boolean allowClose,
                                 boolean allowLiquidation, Long adminUserId) {
        fxPauseBehaviorMapper.updateByCategory(category, allowOpen, allowClose, allowLiquidation, adminUserId);
        // 写后失效全量快照：下次读 loadSnapshot 见 null 即整表回源重填 → console 改后即时生效（不等 TTL）。
        snapshot.set(null);
    }

    /** 取全量快照；命中未过期直接返回，否则整表回源重填（惰性过期）。 */
    private CachedSnapshot loadSnapshot() {
        Instant now = clock.get();
        CachedSnapshot cached = snapshot.get();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached;
        }
        List<FxPauseBehavior> fresh = fxPauseBehaviorMapper.selectAll().stream()
                .map(this::toDomain)
                .toList();
        CachedSnapshot refreshed = new CachedSnapshot(
                fresh,
                fresh.stream().collect(Collectors.toMap(FxPauseBehavior::category, Function.identity())),
                now.plus(cacheTtl));
        snapshot.set(refreshed);
        return refreshed;
    }

    /** TINYINT 1/0 → boolean（非 1 一律视为 false）。 */
    private FxPauseBehavior toDomain(FxPauseBehaviorRecord record) {
        return new FxPauseBehavior(
                record.category(),
                record.categoryName(),
                isTrue(record.allowOpen()),
                isTrue(record.allowClose()),
                isTrue(record.allowLiquidation()));
    }

    private static boolean isTrue(Integer tinyint) {
        return tinyint != null && tinyint == 1;
    }

    /**
     * 全量快照：升序列表 + category 索引 Map + 过期时间戳。
     *
     * @param ordered 升序行为开关列表（findAll 返回）
     * @param byCategory category → behavior 索引（findByCategory 走此 Map）
     * @param expiresAt 快照过期时间
     */
    private record CachedSnapshot(List<FxPauseBehavior> ordered,
                                  Map<Integer, FxPauseBehavior> byCategory,
                                  Instant expiresAt) {
    }
}
