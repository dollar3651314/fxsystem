package com.falconx.trading.engine;

import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingPositionCloseApplicationService;
import com.falconx.trading.dto.PriceTickProcessingResult;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionCloseReason;
import com.falconx.trading.entity.TradingQuoteQualityStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingRiskExposure;
import com.falconx.trading.entity.TradingRiskSwitch;
import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.entity.FxPauseBehavior;
import com.falconx.trading.repository.FxPauseBehaviorRepository;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.RedisTradingRiskSwitchCache;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.repository.TradingRiskControlActionRepository;
import com.falconx.trading.repository.TradingRiskExposureRepository;
import java.util.Optional;
import com.falconx.trading.service.TradingRiskObservabilityService;
import com.falconx.trading.service.TradingScheduleService;
import com.falconx.trading.support.TradingPricingSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 报价驱动引擎。
 *
 * <p>该引擎承接 `market-service` 推来的最新价格事件，并在保存 Redis 最新价后，
 * 只基于 `OpenPositionSnapshotStore` 判定 TP / SL / 强平，不按 tick 扫 MySQL。
 */
@Component
public class QuoteDrivenEngine {

    private static final Logger log = LoggerFactory.getLogger(QuoteDrivenEngine.class);

    private final TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository;
    private final OpenPositionSnapshotStore openPositionSnapshotStore;
    private final PositionTriggerRuleEvaluator positionTriggerRuleEvaluator;
    private final TradingPositionCloseApplicationService tradingPositionCloseApplicationService;
    private final TradingRiskObservabilityService tradingRiskObservabilityService;
    private final TradingScheduleService tradingScheduleService;
    private final RedisTradingRiskSwitchCache riskSwitchCache;
    private final com.falconx.trading.websocket.TradingUserRealtimePushService realtimePushService;
    private final com.falconx.trading.websocket.PositionPnlPushThrottler pnlPushThrottler;
    private final com.falconx.trading.websocket.TradingAdminRealtimePushService adminRealtimePushService;
    private final com.falconx.trading.websocket.AdminExposurePushThrottler exposurePushThrottler;
    private final com.falconx.trading.websocket.AdminPositionPushThrottler adminPositionPushThrottler;
    private final com.falconx.trading.websocket.AdminPositionSummaryPushThrottler adminPositionSummaryPushThrottler;
    private final TradingRiskExposureRepository tradingRiskExposureRepository;
    // STAGE-3-PENDING-ORDER：挂单触发链路
    private final com.falconx.trading.repository.TradingPendingOrderTriggerRepository pendingOrderRepository;
    private final PendingOrderTriggerEvaluator pendingOrderTriggerEvaluator;
    private final com.falconx.trading.application.TradingPendingOrderApplicationService pendingOrderApplicationService;
    private final com.falconx.trading.application.TradingOrderPlacementApplicationService tradingOrderPlacementApplicationService;
    // STAGE-4-PRICE-ALERT：价格告警触发链路
    private final com.falconx.trading.repository.TradingPriceAlertRepository priceAlertRepository;
    private final PriceAlertEvaluator priceAlertEvaluator;
    private final com.falconx.trading.application.TradingPriceAlertApplicationService priceAlertApplicationService;
    private final SymbolTriggerActivityRegistry triggerActivityRegistry;
    // STAGE-14C1 Task 9：账户 MarginLevel 实时重算 + StopOut 双触发
    private final AccountMarginEvaluator accountMarginEvaluator;
    private final AccountMarginStateCache accountMarginStateCache;
    private final com.falconx.trading.application.TradingNotificationApplicationService notificationService;
    // STAGE-14C2 Task 6：FX_PAUSED 按类目控制被动强平（allow_liquidation）
    private final TradingRiskControlActionRepository riskControlActionRepository;
    private final MarketSymbolSpecRepository marketSymbolSpecRepository;
    private final FxPauseBehaviorRepository fxPauseBehaviorRepository;
    // STAGE-14D2 Task 4：CROSS 账户级强平编排（账户级 ML 触发 + 浮亏最大优先逐仓平 + user-level 锁）
    private final com.falconx.trading.application.CrossLiquidationOrchestrator crossLiquidationOrchestrator;

    public QuoteDrivenEngine(TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository,
                             OpenPositionSnapshotStore openPositionSnapshotStore,
                             PositionTriggerRuleEvaluator positionTriggerRuleEvaluator,
                             TradingPositionCloseApplicationService tradingPositionCloseApplicationService,
                             TradingRiskObservabilityService tradingRiskObservabilityService,
                             TradingScheduleService tradingScheduleService,
                             RedisTradingRiskSwitchCache riskSwitchCache,
                             com.falconx.trading.websocket.TradingUserRealtimePushService realtimePushService,
                             com.falconx.trading.websocket.PositionPnlPushThrottler pnlPushThrottler,
                             com.falconx.trading.websocket.TradingAdminRealtimePushService adminRealtimePushService,
                             com.falconx.trading.websocket.AdminExposurePushThrottler exposurePushThrottler,
                             com.falconx.trading.websocket.AdminPositionPushThrottler adminPositionPushThrottler,
                             com.falconx.trading.websocket.AdminPositionSummaryPushThrottler adminPositionSummaryPushThrottler,
                             TradingRiskExposureRepository tradingRiskExposureRepository,
                             com.falconx.trading.repository.TradingPendingOrderTriggerRepository pendingOrderRepository,
                             PendingOrderTriggerEvaluator pendingOrderTriggerEvaluator,
                             com.falconx.trading.application.TradingPendingOrderApplicationService pendingOrderApplicationService,
                             com.falconx.trading.application.TradingOrderPlacementApplicationService tradingOrderPlacementApplicationService,
                             com.falconx.trading.repository.TradingPriceAlertRepository priceAlertRepository,
                             PriceAlertEvaluator priceAlertEvaluator,
                             com.falconx.trading.application.TradingPriceAlertApplicationService priceAlertApplicationService,
                             SymbolTriggerActivityRegistry triggerActivityRegistry,
                             AccountMarginEvaluator accountMarginEvaluator,
                             AccountMarginStateCache accountMarginStateCache,
                             com.falconx.trading.application.TradingNotificationApplicationService notificationService,
                             TradingRiskControlActionRepository riskControlActionRepository,
                             MarketSymbolSpecRepository marketSymbolSpecRepository,
                             FxPauseBehaviorRepository fxPauseBehaviorRepository,
                             com.falconx.trading.application.CrossLiquidationOrchestrator crossLiquidationOrchestrator) {
        this.tradingQuoteSnapshotRepository = tradingQuoteSnapshotRepository;
        this.openPositionSnapshotStore = openPositionSnapshotStore;
        this.positionTriggerRuleEvaluator = positionTriggerRuleEvaluator;
        this.tradingPositionCloseApplicationService = tradingPositionCloseApplicationService;
        this.tradingRiskObservabilityService = tradingRiskObservabilityService;
        this.tradingScheduleService = tradingScheduleService;
        this.riskSwitchCache = riskSwitchCache;
        this.realtimePushService = realtimePushService;
        this.pnlPushThrottler = pnlPushThrottler;
        this.adminRealtimePushService = adminRealtimePushService;
        this.exposurePushThrottler = exposurePushThrottler;
        this.adminPositionPushThrottler = adminPositionPushThrottler;
        this.adminPositionSummaryPushThrottler = adminPositionSummaryPushThrottler;
        this.tradingRiskExposureRepository = tradingRiskExposureRepository;
        this.pendingOrderRepository = pendingOrderRepository;
        this.pendingOrderTriggerEvaluator = pendingOrderTriggerEvaluator;
        this.pendingOrderApplicationService = pendingOrderApplicationService;
        this.tradingOrderPlacementApplicationService = tradingOrderPlacementApplicationService;
        this.priceAlertRepository = priceAlertRepository;
        this.priceAlertEvaluator = priceAlertEvaluator;
        this.priceAlertApplicationService = priceAlertApplicationService;
        this.triggerActivityRegistry = triggerActivityRegistry;
        this.accountMarginEvaluator = accountMarginEvaluator;
        this.accountMarginStateCache = accountMarginStateCache;
        this.notificationService = notificationService;
        this.riskControlActionRepository = riskControlActionRepository;
        this.marketSymbolSpecRepository = marketSymbolSpecRepository;
        this.fxPauseBehaviorRepository = fxPauseBehaviorRepository;
        this.crossLiquidationOrchestrator = crossLiquidationOrchestrator;
    }

    /**
     * 处理一条价格 tick。
     *
     * @param payload 市场价格事件
     * @return 处理结果
     */
    public PriceTickProcessingResult processTick(MarketPriceTickEventPayload payload) {
        // 2026-05-20 perf: per-tick 高频路径，从 INFO 降到 DEBUG。异常/触发风控时仍 INFO 在下方分支
        log.debug("trading.quote.tick.received symbol={} ts={} stale={} quoteStatus={} qualityReason={}",
                payload.symbol(),
                payload.ts(),
                payload.stale(),
                payload.quoteStatus(),
                payload.qualityReason());
        TradingQuoteQualityStatus qualityStatus = TradingQuoteQualityStatus.parse(payload.quoteStatus(), payload.stale());
        TradingQuoteSnapshot snapshot = tradingQuoteSnapshotRepository.save(new TradingQuoteSnapshot(
                payload.symbol(),
                payload.bid(),
                payload.ask(),
                payload.mark(),
                payload.ts(),
                payload.source(),
                qualityStatus.stale(),
                qualityStatus,
                payload.qualityReason()
        ));
        if (!snapshot.executable()) {
            log.warn("trading.quote.tick.non-executable symbol={} source={} ts={} quoteStatus={} reason={} action=snapshot-only",
                    snapshot.symbol(),
                    snapshot.source(),
                    snapshot.ts(),
                    snapshot.qualityStatus(),
                    snapshot.qualityReason());
            return new PriceTickProcessingResult(snapshot, 0);
        }
        if (!tradingScheduleService.isOpenAllowed(snapshot.symbol(), OffsetDateTime.now())) {
            log.warn("trading.quote.tick.market-closed symbol={} source={} ts={} action=snapshot-only",
                    snapshot.symbol(),
                    snapshot.source(),
                    snapshot.ts());
            return new PriceTickProcessingResult(snapshot, 0);
        }

        List<TradingPosition> openPositions = openPositionSnapshotStore.listOpenBySymbol(snapshot.symbol());
        int triggeredActions = 0;
        RuntimeException firstFailure = null;
        // STAGE-14D2 Task 4：本 tick 涉及的 CROSS 用户（同 tick 内同 userId 去重，账户级评估每用户至多一次）。
        // ISOLATED 仓走下方既有单仓双触发路径（C1）不变；CROSS/ISOLATED 不混账户（切换闸门保证），
        // 故按 position.marginMode() 分流安全。
        java.util.Set<Long> crossUserIds = new java.util.LinkedHashSet<>();
        for (TradingPosition position : openPositions) {
            // STAGE-14D2 Task 4：CROSS 仓不走 ISOLATED 单仓双触发（liqPrice=null + 账户级 ML 触发），
            // 收集 userId 在本 tick 收尾时按账户级编排强平（user-level 锁内排序逐仓平）。
            if (position.marginMode() == com.falconx.trading.entity.TradingMarginMode.CROSS) {
                crossUserIds.add(position.userId());
                continue;
            }
            // STAGE-12-GROUP-MARKUP: 用 position 冻结的 bidExtra/askExtra 算 effectiveMark
            // 让强平判定与 entryPrice 含 markup 口径对齐
            BigDecimal effectiveMarkPrice = TradingPricingSupport.resolvePositionMarkPrice(snapshot, position);

            // ① 既有 liqPrice 价格触发（TP / SL / 强平）
            TradingPositionCloseReason closeReason = positionTriggerRuleEvaluator.evaluate(position, effectiveMarkPrice);

            // ② STAGE-14C1 Task 9：账户单仓 MarginLevel 实时重算 + StopOut 触发（master §6.3 ISOLATED 双触发）。
            //    MarginCall 告警 + 节流在 AccountMarginEvaluator/MarginLevelMonitor 内完成，不在此强平；
            //    FX 不可用 → marginLevel=null → StopOut 不触发（liqPrice 仍独立生效）。
            //    返回值为触发时的实时 marginLevel（非 null 即触发），供 STOP_OUT_TRIGGERED 通知展示真实数值。
            BigDecimal stopOutMarginLevel = accountMarginEvaluator.stopOutMarginLevel(position, effectiveMarkPrice);
            boolean stopOutTriggered = stopOutMarginLevel != null;

            // 双触发归一：liqPrice 命中 或 StopOut，任一即按 LIQUIDATION 强平。
            // 同一 tick 同一仓位只调一次 closePositionByTrigger；已 CLOSED / 二次校验由其内部 FOR UPDATE 兜住。
            boolean liquidate = closeReason == TradingPositionCloseReason.LIQUIDATION || stopOutTriggered;
            TradingPositionCloseReason effectiveReason = liquidate ? TradingPositionCloseReason.LIQUIDATION : closeReason;
            if (effectiveReason == null) {
                continue;
            }
            try {
                if (effectiveReason == TradingPositionCloseReason.LIQUIDATION) {
                    // STAGE-14C2 Task 6：FX_PAUSED/GLOBAL_PAUSE 活跃时，按品种类目（master §6.5）
                    // 判断是否允许被动强平。forex/metal 默认 allow_liquidation=false → skip。
                    if (shouldSkipLiquidationDuringPause(snapshot.symbol())) {
                        log.warn("trading.liquidation.skip.fx-paused symbol={} positionId={} liquidationPrice={} markPrice={} stopOut={} reason=allow_liquidation_false",
                                snapshot.symbol(),
                                position.positionId(),
                                position.liquidationPrice(),
                                effectiveMarkPrice,
                                stopOutTriggered);
                        continue;
                    }
                    if (!riskSwitchCache.isEnabled(TradingRiskSwitch.KEY_AUTO_LIQUIDATE_ENABLED, true)) {
                        log.warn("trading.liquidation.skip.auto-paused symbol={} positionId={} liquidationPrice={} markPrice={} stopOut={}",
                                snapshot.symbol(),
                                position.positionId(),
                                position.liquidationPrice(),
                                effectiveMarkPrice,
                                stopOutTriggered);
                        continue;
                    }
                    log.warn("trading.liquidation.triggered symbol={} positionId={} userId={} liquidationPrice={} markPrice={} liqPriceHit={} stopOut={} quoteTs={} quoteSource={}",
                            snapshot.symbol(),
                            position.positionId(),
                            position.userId(),
                            position.liquidationPrice(),
                            effectiveMarkPrice,
                            closeReason == TradingPositionCloseReason.LIQUIDATION,
                            stopOutTriggered,
                            snapshot.ts(),
                            snapshot.source());
                }
                if (tradingPositionCloseApplicationService.closePositionByTrigger(position.positionId(), effectiveReason, snapshot) != null) {
                    triggeredActions++;
                    // 平仓改了 balance/frozen/marginUsed → 失效该用户账户缓存（缓存三要素「刷新」）
                    accountMarginStateCache.invalidate(position.userId());
                    // StopOut 路径强平成功后补发 STOP_OUT_TRIGGERED（与 close 服务 afterCommit 的
                    // POSITION_LIQUIDATED 并存：前者强调「保证金率触发强平线」，后者为通用强平通知，不冲突）。
                    // 仅 liqPrice 触发（非 StopOut）不发，避免对纯价格强平误报保证金率。
                    if (stopOutTriggered) {
                        sendStopOutNotification(position, stopOutMarginLevel);
                    }
                }
            } catch (RuntimeException exception) {
                log.error("trading.quote.tick.position-close.failed symbol={} positionId={} requestedReason={} markPrice={} stopOut={} message={}",
                        snapshot.symbol(),
                        position.positionId(),
                        effectiveReason,
                        effectiveMarkPrice,
                        stopOutTriggered,
                        exception.getMessage(),
                        exception);
                if (firstFailure == null) {
                    firstFailure = exception;
                }
            }
        }

        // STAGE-14D2 Task 4：本 tick 涉及的 CROSS 用户做账户级 ML 评估 + 浮亏最大优先逐仓强平直到恢复。
        // 编排内 Redisson user-level 锁（cross-liq:userId，tryLock 0 跳过）串行同用户多 symbol tick；
        // FX_PAUSED/auto-liquidate 闸门由 closePositionByTrigger 上游门控不在此重复（CROSS 强平为账户级保护性动作）。
        for (Long crossUserId : crossUserIds) {
            try {
                // accountCurrency 由编排内部用 settlementToken 解析（CROSS 账户统一结算币），此处传 null。
                java.util.List<Long> liquidated =
                        crossLiquidationOrchestrator.evaluateAndLiquidate(crossUserId, null);
                triggeredActions += liquidated.size();
            } catch (RuntimeException exception) {
                log.error("trading.quote.tick.cross-liquidation.failed symbol={} userId={} message={}",
                        snapshot.symbol(), crossUserId, exception.getMessage(), exception);
                if (firstFailure == null) {
                    firstFailure = exception;
                }
            }
        }

        // STAGE-3-PENDING-ORDER：扫描该 symbol 全部 PENDING 挂单并触发命中
        // 与 PositionTriggerRuleEvaluator 并行跑；SL_TP 在迁移期同时受持仓字段 + 挂单表两条触发链路保护
        try {
            triggerPendingOrders(snapshot);
        } catch (RuntimeException exception) {
            log.error("trading.quote.tick.pending-order.failed symbol={} message={}",
                    snapshot.symbol(), exception.getMessage(), exception);
            if (firstFailure == null) {
                firstFailure = exception;
            }
        }

        // STAGE-4-PRICE-ALERT：扫描该 symbol 全部 ACTIVE 告警并触发命中
        // 5min 节流由 SQL 层 selectTriggerableBySymbol 过滤；trigger_count 3 次上限由 CAS 保证
        try {
            triggerPriceAlerts(snapshot);
        } catch (RuntimeException exception) {
            log.error("trading.quote.tick.price-alert.failed symbol={} message={}",
                    snapshot.symbol(), exception.getMessage(), exception);
            if (firstFailure == null) {
                firstFailure = exception;
            }
        }

        try {
            tradingRiskObservabilityService.refreshExposureFromQuote(snapshot, OffsetDateTime.now());
        } catch (RuntimeException exception) {
            log.error("trading.quote.tick.exposure-refresh.failed symbol={} markPrice={} message={}",
                    snapshot.symbol(),
                    snapshot.mark(),
                    exception.getMessage(),
                    exception);
            if (firstFailure == null) {
                firstFailure = exception;
            }
        }

        // STAGE-2-REALTIME-DATA Phase 2：每条 tick refresh 后推 admin exposure（按 symbol 200ms 节流）
        // 失败不影响主路径
        if (exposurePushThrottler.shouldPush(snapshot.symbol())) {
            try {
                TradingRiskExposure exposure = tradingRiskExposureRepository.findBySymbol(snapshot.symbol()).orElse(null);
                if (exposure != null) {
                    adminRealtimePushService.publishExposureUpdate(exposure, snapshot.ts());
                }
            } catch (RuntimeException exception) {
                log.warn("trading.quote.tick.admin-exposure.push.failed symbol={} message={}",
                        snapshot.symbol(), exception.getMessage());
            }
        }

        // STAGE-2-REALTIME-DATA Phase 1：每条 tick 后推 PnL 给该 symbol 所有持仓所有者
        // 按 symbol 100ms 节流（pnlPushThrottler.shouldPush 内部 CAS）
        // 失败不影响开仓 / 平仓 / 强平触发路径
        if (!openPositions.isEmpty() && snapshot.mark() != null && pnlPushThrottler.shouldPush(snapshot.symbol())) {
            try {
                int recipients = realtimePushService.publishPositionPnlUpdates(openPositions, snapshot.mark(), snapshot);
                if (recipients > 0) {
                    log.debug("trading.quote.tick.pnl.pushed symbol={} positions={} recipients={}",
                            snapshot.symbol(), openPositions.size(), recipients);
                }
            } catch (RuntimeException exception) {
                log.warn("trading.quote.tick.pnl.push.failed symbol={} message={}",
                        snapshot.symbol(), exception.getMessage());
            }
        }

        // 同步给 admin 持仓监控页推 PnL patch（按 symbol 200ms 节流，与 admin.exposure 同节奏）
        // 复用 user push 同一份 openPositions + snapshot，admin push service 内部按 side 重算 markPrice
        // 同时聚合器 updateSymbol(symbol, ΣunrealizedPnl) 在 publishPositionPnlUpdates 内部完成
        if (!openPositions.isEmpty() && snapshot.mark() != null
                && adminPositionPushThrottler.shouldPush(snapshot.symbol())) {
            try {
                adminRealtimePushService.publishPositionPnlUpdates(
                        snapshot.symbol(), openPositions, snapshot, snapshot.ts());
            } catch (RuntimeException exception) {
                log.warn("trading.quote.tick.admin-position.push.failed symbol={} message={}",
                        snapshot.symbol(), exception.getMessage());
            }
        }

        // 平台 totalUnrealizedPnl 汇总 patch（全局 500ms 节流）
        // 聚合器在 publishPositionPnlUpdates 内部已经写过 per-symbol 数据，这里只读 sum 并推
        if (adminPositionSummaryPushThrottler.shouldPush()) {
            try {
                adminRealtimePushService.publishPositionSummary(snapshot.ts());
            } catch (RuntimeException exception) {
                log.warn("trading.quote.tick.admin-position-summary.push.failed symbol={} message={}",
                        snapshot.symbol(), exception.getMessage());
            }
        }

        if (firstFailure != null) {
            log.warn("trading.quote.tick.completed.with-failures symbol={} source={} stale={} openPositions={} triggeredActions={} firstFailure={}",
                    snapshot.symbol(),
                    snapshot.source(),
                    snapshot.stale(),
                    openPositions.size(),
                    triggeredActions,
                    firstFailure.getMessage());
            throw firstFailure;
        }

        // 2026-05-20 perf: 仅当 triggeredActions > 0（实际触发了风控/TP/SL）才在 INFO 留痕，
        // 其余正常 tick 落 DEBUG 避免 10K/s 写日志
        if (triggeredActions > 0) {
            log.info("trading.quote.tick.applied symbol={} source={} stale={} openPositions={} triggeredActions={}",
                    snapshot.symbol(),
                    snapshot.source(),
                    snapshot.stale(),
                    openPositions.size(),
                    triggeredActions);
        } else if (log.isDebugEnabled()) {
            log.debug("trading.quote.tick.applied symbol={} source={} stale={} openPositions={} triggeredActions=0",
                    snapshot.symbol(),
                    snapshot.source(),
                    snapshot.stale(),
                    openPositions.size());
        }
        return new PriceTickProcessingResult(snapshot, triggeredActions);
    }

    /**
     * STAGE-14C1 Task 9：StopOut 强平成功后补发 {@code STOP_OUT_TRIGGERED} 站内信。
     *
     * <p>对齐 V31 模板占位 {@code ${marginLevel}} / {@code ${symbol}}。{@code marginLevel} 传 StopOut
     * 触发时由 {@code AccountMarginEvaluator} 算好的真实保证金率数值（百分比，2 位 HALF_UP，如 {@code 25.34}），
     * 不复算（避免二次读账户 / FX），渲染为「保证金率 25.34% 触发强平…」。
     * 正常 StopOut 路径 marginLevel 必非 null；防御性地若为 null 则退化省略该占位（不阻断通知）。
     * 发送失败不影响强平主路径（已落库 + POSITION_LIQUIDATED 通知由 close 服务 afterCommit 发出）。
     */
    /**
     * STAGE-14C2 Task 6：FX_PAUSED/GLOBAL_PAUSE 活跃时，按品种类目（master §6.5）判断是否跳过被动强平。
     *
     * <ul>
     *   <li>非 pause 态 → 不跳过（false），既有强平照常。</li>
     *   <li>pause 态 + category 非 null + behavior 命中 + allow_liquidation=false → 跳过（true）。</li>
     *   <li><b>保守降级（与开仓相反方向）</b>：pause 态但 spec 缺失 / category==null（过渡期旧快照）
     *     / behavior 缺失 → <b>不跳过</b>（false），保持 C1 现状强平继续。强平是保护性动作，缺信息时
     *     倾向继续执行以防穿仓（开仓缺信息从严全拒，强平缺信息从宽继续——两者方向相反，由各自风险性质决定）。</li>
     * </ul>
     */
    private boolean shouldSkipLiquidationDuringPause(String symbol) {
        if (!riskControlActionRepository.hasActiveGlobalPause()) {
            return false;
        }
        SymbolSpec spec = marketSymbolSpecRepository.findByPlatformSymbol(symbol).orElse(null);
        Integer category = spec == null ? null : spec.category();
        if (category == null) {
            // 降级：缺 category → 不阻止强平（防穿仓），保持现状继续强平。
            log.warn("trading.fxpause.liquidation.degrade.category-null symbol={} action=liquidate-continue",
                    symbol);
            return false;
        }
        Optional<FxPauseBehavior> behavior = fxPauseBehaviorRepository.findByCategory(category);
        if (behavior.isEmpty()) {
            // 降级：behavior 缺失 → 不阻止强平（防穿仓），保持现状继续强平。
            log.warn("trading.fxpause.liquidation.degrade.behavior-missing symbol={} category={} action=liquidate-continue",
                    symbol, category);
            return false;
        }
        // allow_liquidation=false（forex/metal 默认）→ pause 期间跳过被动强平。
        return !behavior.get().allowLiquidation();
    }

    private void sendStopOutNotification(TradingPosition position, BigDecimal marginLevel) {
        if (notificationService == null) {
            return;
        }
        try {
            java.util.Map<String, String> params = marginLevel == null
                    ? java.util.Map.of("symbol", position.symbol())
                    : java.util.Map.of(
                            "marginLevel", marginLevel.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                            "symbol", position.symbol());
            notificationService.send(
                    "STOP_OUT_TRIGGERED",
                    position.userId(),
                    "STOP_OUT_TRIGGERED",
                    params,
                    "RISK",
                    position.positionId(),
                    null);
        } catch (RuntimeException exception) {
            log.warn("trading.margin.stop-out.notification.failed positionId={} userId={} message={}",
                    position.positionId(), position.userId(), exception.toString());
        }
    }

    /**
     * STAGE-4-PRICE-ALERT：扫描 ACTIVE 告警并触发命中。
     *
     * <p>SQL 层已过滤 5min 节流；本入口按 PriceAlertEvaluator 价格条件 + ApplicationService
     * CAS 写入完成触发与 WS push。每条告警单独事务，本入口失败不影响其他告警。
     */
    private void triggerPriceAlerts(TradingQuoteSnapshot snapshot) {
        if (snapshot.mark() == null) return;
        if (!triggerActivityRegistry.mayHavePriceAlert(snapshot.symbol())) {
            return;
        }
        // 必须用 UTC，与 repo 写入 last_triggered_at 的 LocalDateTime.now(UTC) 对齐
        // 用 system zone 会让 5 分钟节流 SQL 条件总成立（时差导致 last_triggered_at 永远落在 5 分钟前）
        java.util.List<com.falconx.trading.entity.TradingPriceAlert> candidates =
                priceAlertRepository.findTriggerableBySymbol(snapshot.symbol(), OffsetDateTime.now(java.time.ZoneOffset.UTC));
        if (candidates.isEmpty()) return;
        for (com.falconx.trading.entity.TradingPriceAlert alert : candidates) {
            if (!priceAlertEvaluator.evaluate(alert, snapshot.mark())) continue;
            try {
                priceAlertApplicationService.trigger(alert.id(), snapshot.mark());
            } catch (RuntimeException exception) {
                log.error("trading.price-alert.trigger.exception id={} message={}",
                        alert.id(), exception.getMessage(), exception);
            }
        }
    }

    /**
     * STAGE-3-PENDING-ORDER：扫描 PENDING 挂单并触发命中。
     *
     * <p>每条挂单单独事务由 ApplicationService 保证；本入口失败不影响其他挂单。
     */
    private void triggerPendingOrders(TradingQuoteSnapshot snapshot) {
        if (!triggerActivityRegistry.mayHavePendingOrder(snapshot.symbol())) {
            return;
        }
        java.util.List<com.falconx.trading.entity.TradingPendingOrderTrigger> pending =
                pendingOrderRepository.findPendingBySymbol(snapshot.symbol());
        if (pending.isEmpty()) {
            triggerActivityRegistry.markPendingOrderInactive(snapshot.symbol());
            return;
        }
        for (com.falconx.trading.entity.TradingPendingOrderTrigger order : pending) {
            if (!pendingOrderTriggerEvaluator.evaluate(order, snapshot)) {
                continue;
            }
            try {
                if (order.orderType() == com.falconx.trading.entity.TradingPendingOrderType.SL_TP) {
                    pendingOrderApplicationService.triggerSlTp(order.id(),
                            tradingPositionCloseApplicationService, snapshot);
                } else {
                    pendingOrderApplicationService.triggerOpening(order.id(),
                            tradingOrderPlacementApplicationService);
                }
            } catch (RuntimeException exception) {
                log.error("trading.pending-order.trigger.exception id={} orderNo={} message={}",
                        order.id(), order.orderNo(), exception.getMessage(), exception);
            }
        }
    }
}
