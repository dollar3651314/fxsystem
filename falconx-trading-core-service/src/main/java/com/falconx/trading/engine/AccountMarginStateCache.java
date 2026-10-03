package com.falconx.trading.engine;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.repository.TradingAccountRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 账户保证金状态内存缓存（STAGE-14C1 Task 9）。
 *
 * <p><b>用途</b>：{@code QuoteDrivenEngine.processTick} 每条 tick 对该 symbol 上有 OPEN 持仓的每个用户
 * 实时重算 MarginLevel（StopOut 判定）时，需要读账户 {@code balance/frozen/marginUsed}。
 * 行情高频（万级/秒），若每 tick 每用户直打 MySQL 会打死 DB（AGENTS §3.9 强约束 + 计划 Task 9 Step 3）。
 * 本缓存把账户读收敛到「短 TTL 内单用户至多一次 DB 读」。
 *
 * <p><b>缓存三要素（AGENTS §3.9，必须三项齐全）</b>：
 * <ul>
 *   <li><b>TTL</b>：{@link #TTL} = 1s。tick 频率远高于 1s，1s 内同一用户的多次重算复用同一份账户快照；
 *       1s 误差对 StopOut 判定可接受（balance/frozen/marginUsed 仅在开 / 平 / 落账时变，且这些路径会显式
 *       {@link #invalidate}）。</li>
 *   <li><b>刷新</b>：事件驱动失效 —— 开仓 / 平仓 / 强平落账等会改 balance/frozen/marginUsed 的写路径，
 *       完成后调 {@link #invalidate(Long)} 让该用户下一次读穿透到 DB；叠加 TTL 兜底过期。</li>
 *   <li><b>降级</b>：cache miss / 过期 / 失效一律读 owner Repository
 *       {@link TradingAccountRepository#findByUserIdAndCurrency}（不加锁，只读判定用）；DB 也查不到（账户不存在）
 *       返回 {@code null}，caller 据此跳过该用户 MarginLevel 判定（liqPrice 仍独立生效）。</li>
 * </ul>
 *
 * <p><b>口径说明</b>：读用不加锁的 {@code findByUserIdAndCurrency}。MarginLevel 实时判定只需近似账户快照
 * 触发候选；真正强平由 {@code closePositionByTrigger} 内 {@code SELECT ... FOR UPDATE} + 二次校验兜住一致性，
 * 故此处不需要悲观锁。这与 {@link OpenPositionSnapshotStore}「高频路径只读内存、写一致性交给事务」的模式一致。
 *
 * <p><b>线程模型</b>：{@link ConcurrentHashMap} 承载并发 tick 线程读写；不在回调线程做 DB 业务链（AGENTS §3.8.2）——
 * 本缓存只做一次轻量 owner 读，复用 {@code QuoteDrivenEngine} 既有执行线程，不新增回调线程业务。
 */
@Component
public class AccountMarginStateCache {

    private static final Logger log = LoggerFactory.getLogger(AccountMarginStateCache.class);

    /** 账户快照 TTL（短）：1s。 */
    static final Duration TTL = Duration.ofSeconds(1);

    private final TradingAccountRepository accountRepository;
    private final Supplier<Instant> clock;

    /** userId → 缓存的账户快照（含写入时刻，用于 TTL 判定）。 */
    private final ConcurrentHashMap<Long, CachedAccount> cache = new ConcurrentHashMap<>();

    @Autowired
    public AccountMarginStateCache(TradingAccountRepository accountRepository) {
        this(accountRepository, Instant::now);
    }

    /** 测试用构造：注入可控时钟。 */
    AccountMarginStateCache(TradingAccountRepository accountRepository, Supplier<Instant> clock) {
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    /**
     * 读账户快照：TTL 内命中缓存，否则降级读 owner Repository 并回填缓存。
     *
     * @param userId   用户 ID
     * @param currency 账户币（结算币 / settlement token）
     * @return 账户快照；账户不存在返回 {@code null}（caller 跳过 MarginLevel 判定）
     */
    public TradingAccount getAccount(Long userId, String currency) {
        Instant now = clock.get();
        CachedAccount cached = cache.get(userId);
        if (cached != null && now.isBefore(cached.expiresAt()) && cached.account() != null) {
            return cached.account();
        }
        // cache miss / 过期 / 失效 → 降级读 DB（不加锁，判定用近似快照）
        TradingAccount fresh = accountRepository.findByUserIdAndCurrency(userId, currency).orElse(null);
        cache.put(userId, new CachedAccount(fresh, now.plus(TTL)));
        if (fresh == null) {
            log.debug("trading.margin.account-cache.miss-db-empty userId={} currency={}", userId, currency);
        }
        return fresh;
    }

    /**
     * 失效某用户缓存（开 / 平 / 落账等改 balance/frozen/marginUsed 的写路径完成后调用）。
     *
     * @param userId 用户 ID
     */
    public void invalidate(Long userId) {
        if (userId == null) {
            return;
        }
        cache.remove(userId);
    }

    /** 缓存条目：账户快照（可为 null 表示「DB 也无此账户」）+ 过期时刻。 */
    private record CachedAccount(TradingAccount account, Instant expiresAt) {
    }
}
