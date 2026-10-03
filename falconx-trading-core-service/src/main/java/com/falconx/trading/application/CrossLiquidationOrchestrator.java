package com.falconx.trading.application;

import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.dto.PositionCloseResult;
import com.falconx.trading.engine.AccountMarginStateCache;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionCloseReason;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.service.AccountEquityCalculator;
import com.falconx.trading.service.AccountEquityCalculator.PositionMarkInput;
import com.falconx.trading.service.FxRateService;
import com.falconx.trading.service.MarginLevelMonitor;
import com.falconx.trading.service.model.AccountMarginState;
import com.falconx.trading.support.PnlResult;
import com.falconx.trading.support.TradingPricingSupport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * CROSS 账户级强平编排（STAGE-14D2 Task 4，master §6.3）。
 *
 * <p><b>触发与算法</b>（{@link #evaluateAndLiquidate}）：
 * <ol>
 *   <li><b>Redisson user-level 锁</b> {@code cross-liq:{userId}}，{@code tryLock(0, ...)} 不等待——
 *       获取不到说明该用户正在被另一 tick 强平评估，直接跳过本次（下个 tick 再来），<b>不阻塞 tick 线程</b>。</li>
 *   <li>锁内取该用户全部 OPEN 持仓（{@link OpenPositionSnapshotStore#listOpenByUserId}）；空 → 返回。</li>
 *   <li>读账户只读快照（{@link AccountMarginStateCache}，与 close 服务的 FOR UPDATE 不争锁），
 *       各仓取最新 quote + quoteCurrency 组 {@link PositionMarkInput}。</li>
 *   <li>{@link AccountEquityCalculator#computeAccountMarginLevel} 算账户级 ML。</li>
 *   <li>ML==null（FX 不可用降级 / 无持仓）或 ML/100 &gt; stopOut → 不强平，返回。</li>
 *   <li>ML/100 ≤ stopOut：按 {@code |uPnL_i(AC)|} 降序排序（FX 不可用的仓 uPnL=null 视作 0 排末尾）→
 *       逐仓 {@code closePositionByTrigger(positionId, CROSS_STOP_OUT, quote)} → 平成功后
 *       {@link AccountMarginStateCache#invalidate} + 重新取剩余 OPEN 持仓重算 ML → ML/100 &gt; stopOut 即止步。</li>
 * </ol>
 *
 * <p><b>锁与 FOR UPDATE 不死锁</b>：评估全程只读账户快照（不持 DB 行锁）；真正落账由
 * {@code closePositionByTrigger} 内 {@code SELECT ... FOR UPDATE} 各自获取行锁后立即提交，
 * user-level 锁只串行化「同一用户多 symbol tick 的账户级评估」，与单仓 FOR UPDATE 维度正交。
 *
 * <p><b>线程模型</b>（AGENTS §3.8.2）：复用 {@code QuoteDrivenEngine} 既有执行线程，不新增回调线程业务。
 *
 * <p><b>账户级通知</b>（STAGE-14D2 Task 5，master §6.3）：逐仓强平结束后若强平数 &gt; 0，
 * 发<b>一条</b>账户级 {@code CROSS_STOP_OUT_TRIGGERED}（params: marginLevel=触发时账户 ML / count=强平数 /
 * symbols=强平 symbol 逗号清单，relatedKey="ACCOUNT"，relatedId=accountId）。CROSS 强平的逐仓 close
 * 已在 {@code TradingPositionCloseApplicationService.publishPositionNotification} 跳过逐仓 POSITION_LIQUIDATED
 * （去重），改由此处一次汇总。{@link #evaluateAndLiquidate} 仍返回已强平 positionId 清单。
 */
@Service
public class CrossLiquidationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CrossLiquidationOrchestrator.class);

    /** Redisson user-level 锁 key 前缀。 */
    private static final String LOCK_PREFIX = "cross-liq:";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final OpenPositionSnapshotStore openPositionSnapshotStore;
    private final AccountMarginStateCache accountMarginStateCache;
    private final AccountEquityCalculator accountEquityCalculator;
    private final MarketSymbolSpecRepository marketSymbolSpecRepository;
    private final TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository;
    private final FxRateService fxRateService;
    private final MarginLevelMonitor marginLevelMonitor;
    private final TradingPositionCloseApplicationService tradingPositionCloseApplicationService;
    private final TradingNotificationApplicationService notificationService;
    private final RedissonClient redissonClient;
    /** 结算币（账户币 AC），与 close 服务 settlementToken 一致。 */
    private final String settlementCurrency;

    @Autowired
    public CrossLiquidationOrchestrator(OpenPositionSnapshotStore openPositionSnapshotStore,
                                        AccountMarginStateCache accountMarginStateCache,
                                        AccountEquityCalculator accountEquityCalculator,
                                        MarketSymbolSpecRepository marketSymbolSpecRepository,
                                        TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository,
                                        FxRateService fxRateService,
                                        MarginLevelMonitor marginLevelMonitor,
                                        TradingPositionCloseApplicationService tradingPositionCloseApplicationService,
                                        TradingNotificationApplicationService notificationService,
                                        RedissonClient redissonClient,
                                        TradingCoreServiceProperties properties) {
        this(openPositionSnapshotStore, accountMarginStateCache, accountEquityCalculator,
                marketSymbolSpecRepository, tradingQuoteSnapshotRepository, fxRateService,
                marginLevelMonitor, tradingPositionCloseApplicationService, notificationService, redissonClient,
                properties.getSettlementToken());
    }

    /** 测试用构造：直接注入结算币。 */
    CrossLiquidationOrchestrator(OpenPositionSnapshotStore openPositionSnapshotStore,
                                 AccountMarginStateCache accountMarginStateCache,
                                 AccountEquityCalculator accountEquityCalculator,
                                 MarketSymbolSpecRepository marketSymbolSpecRepository,
                                 TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository,
                                 FxRateService fxRateService,
                                 MarginLevelMonitor marginLevelMonitor,
                                 TradingPositionCloseApplicationService tradingPositionCloseApplicationService,
                                 TradingNotificationApplicationService notificationService,
                                 RedissonClient redissonClient,
                                 String settlementCurrency) {
        this.openPositionSnapshotStore = openPositionSnapshotStore;
        this.accountMarginStateCache = accountMarginStateCache;
        this.accountEquityCalculator = accountEquityCalculator;
        this.marketSymbolSpecRepository = marketSymbolSpecRepository;
        this.tradingQuoteSnapshotRepository = tradingQuoteSnapshotRepository;
        this.fxRateService = fxRateService;
        this.marginLevelMonitor = marginLevelMonitor;
        this.tradingPositionCloseApplicationService = tradingPositionCloseApplicationService;
        this.notificationService = notificationService;
        this.redissonClient = redissonClient;
        this.settlementCurrency = settlementCurrency;
    }

    /**
     * 对某 CROSS 用户做账户级 ML 评估 + 浮亏最大优先逐仓强平直到恢复。
     *
     * @param userId          用户 ID
     * @param accountCurrency 账户币（当前与 settlementCurrency 一致；保留参数以对齐 master §6.3 签名）
     * @return 已强平的 positionId 清单（顺序 = 实际强平顺序），未强平或跳过返回空 List（Task 5 据此发账户级通知）
     */
    public List<Long> evaluateAndLiquidate(Long userId, String accountCurrency) {
        if (userId == null) {
            return List.of();
        }
        RLock lock = redissonClient.getLock(LOCK_PREFIX + userId);
        boolean acquired;
        try {
            // tryLock(0, ...)：不等待。获取不到 = 该用户正被另一 tick 评估，跳过本次（下个 tick 再来），不阻塞 tick 线程。
            acquired = lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("trading.cross-liq.lock.interrupted userId={}", userId);
            return List.of();
        }
        if (!acquired) {
            log.debug("trading.cross-liq.lock.skip userId={} reason=concurrent-evaluation", userId);
            return List.of();
        }
        try {
            return liquidateUnderLock(userId, accountCurrency);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 锁内主流程：评估账户级 ML + 浮亏最大优先逐仓平直到恢复。 */
    private List<Long> liquidateUnderLock(Long userId, String accountCurrency) {
        List<TradingPosition> positions = openPositionSnapshotStore.listOpenByUserId(userId);
        if (positions.isEmpty()) {
            return List.of();
        }
        TradingAccount account = accountMarginStateCache.getAccount(userId, settlementCurrency);
        if (account == null) {
            // 账户缺失（异常态）→ 跳过 CROSS 强平判定（与 ISOLATED 同口径降级）。
            log.warn("trading.cross-liq.account-missing userId={} currency={}", userId, settlementCurrency);
            return List.of();
        }

        AccountMarginState state = computeMarginLevel(account, positions);
        BigDecimal stopOutLevel = marginLevelMonitor.currentStopOutLevel();
        if (!breached(state, stopOutLevel)) {
            // ML==null（FX 降级 / 无持仓 / 除零）或 ML > stopOut → 不强平。
            return List.of();
        }

        log.warn("trading.cross-liq.breach userId={} marginLevel={} stopOut={} openPositions={}",
                userId, state.marginLevel(), stopOutLevel, positions.size());

        // 触发时账户 ML（账户级强平的 marginLevel，用于账户级通知文案，逐仓重算前快照）。
        BigDecimal triggerMarginLevel = state.marginLevel();
        List<Long> liquidated = new ArrayList<>();
        // 强平 symbol 清单（保插入顺序 + 去重，按实际强平顺序拼接）。
        Map<String, Boolean> liquidatedSymbols = new LinkedHashMap<>();
        // 首轮按 |uPnL(AC)| 降序排序（FX 不可用的仓 uPnL=null 视作 0 排末尾）。
        List<TradingPosition> ordered = sortByAbsUpnlDesc(positions, account.currency());
        for (TradingPosition position : ordered) {
            TradingQuoteSnapshot quote = tradingQuoteSnapshotRepository.findBySymbol(position.symbol()).orElse(null);
            if (quote == null || !quote.executable()) {
                // 无可成交报价 → 跳过该仓（不阻断其余仓强平；穿仓保护方向从宽继续其余仓）。
                log.warn("trading.cross-liq.skip-position.no-quote userId={} positionId={} symbol={}",
                        userId, position.positionId(), position.symbol());
                continue;
            }
            PositionCloseResult result;
            try {
                result = tradingPositionCloseApplicationService.closePositionByTrigger(
                        position.positionId(), TradingPositionCloseReason.CROSS_STOP_OUT, quote);
            } catch (RuntimeException ex) {
                log.error("trading.cross-liq.close.failed userId={} positionId={} symbol={} message={}",
                        userId, position.positionId(), position.symbol(), ex.getMessage(), ex);
                continue;
            }
            if (result == null) {
                // 已被并发链路平掉（FOR UPDATE + isTerminal 兜底）→ 跳过，继续重算。
                continue;
            }
            liquidated.add(position.positionId());
            liquidatedSymbols.put(position.symbol(), Boolean.TRUE);
            // 平仓改了 balance/frozen/marginUsed → 失效该用户账户缓存。
            accountMarginStateCache.invalidate(userId);

            // 重新取剩余 OPEN 持仓 + 重算账户级 ML；恢复（ML > stopOut 或不可判定）即止步。
            List<TradingPosition> remaining = openPositionSnapshotStore.listOpenByUserId(userId);
            if (remaining.isEmpty()) {
                break;
            }
            TradingAccount refreshed = accountMarginStateCache.getAccount(userId, settlementCurrency);
            if (refreshed == null) {
                break;
            }
            AccountMarginState recomputed = computeMarginLevel(refreshed, remaining);
            if (!breached(recomputed, stopOutLevel)) {
                log.info("trading.cross-liq.recovered userId={} marginLevel={} stopOut={} liquidatedCount={}",
                        userId, recomputed.marginLevel(), stopOutLevel, liquidated.size());
                break;
            }
        }
        if (!liquidated.isEmpty()) {
            log.warn("trading.cross-liq.completed userId={} liquidatedCount={} liquidatedPositionIds={}",
                    userId, liquidated.size(), liquidated);
            // STAGE-14D2 Task 5：账户级强平完成后发一条汇总通知（含触发 ML + 强平数 + symbol 清单），
            //   逐仓 close 已跳过逐仓 POSITION_LIQUIDATED（去重）。
            sendCrossStopOutNotification(userId, account.accountId(), triggerMarginLevel,
                    liquidated.size(), liquidatedSymbols.keySet().stream().collect(Collectors.joining(", ")));
        }
        return liquidated;
    }

    /** 发送账户级 CROSS_STOP_OUT_TRIGGERED 通知；失败不阻断强平主流程（仅 warn）。 */
    private void sendCrossStopOutNotification(Long userId, Long accountId, BigDecimal marginLevel,
                                              int count, String symbols) {
        if (notificationService == null) {
            return;
        }
        String mlStr = marginLevel == null
                ? "—"
                : marginLevel.setScale(2, RoundingMode.HALF_UP).toPlainString();
        try {
            notificationService.send(
                    "CROSS_STOP_OUT_TRIGGERED",
                    userId,
                    "CROSS_STOP_OUT_TRIGGERED",
                    Map.of(
                            "marginLevel", mlStr,
                            "count", String.valueOf(count),
                            "symbols", symbols
                    ),
                    "ACCOUNT",
                    accountId,
                    null);
        } catch (RuntimeException ex) {
            log.warn("trading.cross-liq.notification.send.failed userId={} count={} reason={}",
                    userId, count, ex.toString());
        }
    }

    /** 组 PositionMarkInput（各仓 effectiveMarkPrice + quoteCurrency）→ 账户级 ML。 */
    private AccountMarginState computeMarginLevel(TradingAccount account, List<TradingPosition> positions) {
        List<PositionMarkInput> inputs = new ArrayList<>(positions.size());
        for (TradingPosition position : positions) {
            TradingQuoteSnapshot quote = tradingQuoteSnapshotRepository.findBySymbol(position.symbol()).orElse(null);
            BigDecimal effectiveMark = quote == null
                    ? null
                    : TradingPricingSupport.resolvePositionMarkPrice(quote, position);
            String quoteCurrency = marketSymbolSpecRepository.findByPlatformSymbol(position.symbol())
                    .map(SymbolSpec::quoteCurrency)
                    .orElse(null);
            inputs.add(new PositionMarkInput(position, effectiveMark, quoteCurrency));
        }
        return accountEquityCalculator.computeAccountMarginLevel(account, inputs);
    }

    /** ML 跌穿判定：state/ML 非空且 ML/100 ≤ stopOut。ML==null（降级 / 无持仓）→ 不跌穿。 */
    private boolean breached(AccountMarginState state, BigDecimal stopOutLevel) {
        if (state == null || state.marginLevel() == null) {
            return false;
        }
        // marginLevel 是百分比（×100，如 25.34），stopOut 是小数（0.30）→ ratio = ML/100 比较。
        BigDecimal ratio = state.marginLevel().divide(HUNDRED);
        return ratio.compareTo(stopOutLevel) <= 0;
    }

    /** 按 |uPnL(AC)| 降序排序（FX 不可用 / 无报价的仓 uPnL=null 视作 0，排末尾）。 */
    private List<TradingPosition> sortByAbsUpnlDesc(List<TradingPosition> positions, String accountCurrency) {
        List<TradingPosition> ordered = new ArrayList<>(positions);
        ordered.sort((a, b) -> absUpnl(b, accountCurrency).compareTo(absUpnl(a, accountCurrency)));
        return ordered;
    }

    /** 单仓 |uPnL(AC)|；FX 不可用 / 无报价 / 无 markPrice → 0（排末尾，不优先平不可估值的仓）。 */
    private BigDecimal absUpnl(TradingPosition position, String accountCurrency) {
        TradingQuoteSnapshot quote = tradingQuoteSnapshotRepository.findBySymbol(position.symbol()).orElse(null);
        if (quote == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal effectiveMark = TradingPricingSupport.resolvePositionMarkPrice(quote, position);
        String quoteCurrency = marketSymbolSpecRepository.findByPlatformSymbol(position.symbol())
                .map(SymbolSpec::quoteCurrency)
                .orElse(null);
        PnlResult pnl = TradingPricingSupport.calculatePositionPnlInAccount(
                position, effectiveMark, quoteCurrency, accountCurrency, fxRateService);
        BigDecimal inAccount = pnl.inAccount();
        return inAccount == null ? BigDecimal.ZERO : inAccount.abs();
    }
}
