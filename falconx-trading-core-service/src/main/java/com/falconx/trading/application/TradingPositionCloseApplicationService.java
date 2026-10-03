package com.falconx.trading.application;

import com.falconx.trading.command.CloseTradingPositionCommand;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.dto.PositionCloseResult;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.PositionTriggerRuleEvaluator;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingLedgerBizType;
import com.falconx.trading.entity.TradingLiquidationLog;
import com.falconx.trading.entity.TradingOutboxMessage;
import com.falconx.trading.entity.TradingOutboxStatus;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionCloseReason;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.entity.TradingTradeType;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingLiquidationLogRepository;
import com.falconx.trading.repository.TradingOutboxRepository;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.repository.TradingTradeRepository;
import com.falconx.trading.service.FxRateService;
import com.falconx.trading.service.TradingAccountService;
import com.falconx.trading.service.TradingRiskObservabilityService;
import com.falconx.trading.service.TradingScheduleService;
import com.falconx.trading.support.TradingPricingSupport;
import com.falconx.trading.websocket.TradingUserRealtimePushService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 持仓退出应用服务。
 *
 * <p>该服务收敛手动平仓、TP、SL 和强平的共享 owner 写路径：
 *
 * <ol>
 *   <li>锁定 OPEN 持仓与真实结算账户</li>
 *   <li>基于 Redis 最新标记价计算真实已实现盈亏</li>
 *   <li>同事务更新账户、账本、持仓、成交、净敞口与 outbox</li>
 *   <li>强平额外写 `t_liquidation_log` 并执行负净值保护</li>
 *   <li>事务提交后移除 OPEN 持仓快照</li>
 * </ol>
 */
@Service
public class TradingPositionCloseApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingPositionCloseApplicationService.class);

    private final TradingCoreServiceProperties properties;
    private final TradingPositionRepository tradingPositionRepository;
    private final TradingTradeRepository tradingTradeRepository;
    private final TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository;
    private final TradingAccountService tradingAccountService;
    private final TradingRiskObservabilityService tradingRiskObservabilityService;
    private final TradingOutboxRepository tradingOutboxRepository;
    private final TradingLiquidationLogRepository tradingLiquidationLogRepository;
    private final TradingScheduleService tradingScheduleService;
    private final OpenPositionSnapshotStore openPositionSnapshotStore;
    private final PositionTriggerRuleEvaluator positionTriggerRuleEvaluator;
    private final TradingUserRealtimePushService tradingUserRealtimePushService;
    // STAGE-3-PENDING-ORDER：可选注入；平仓时级联撤 SL_TP
    private final com.falconx.trading.repository.TradingPendingOrderTriggerRepository pendingOrderRepository;
    private final com.falconx.infrastructure.id.IdGenerator idGenerator;
    private final boolean asyncPostProcessEnabled;
    // STAGE-14B Task 9b：平仓/强平 realized PnL 货币换算所需 —— 取 position symbol 的计价币 + fxAtClose。
    private final MarketSymbolSpecRepository marketSymbolSpecRepository;
    private final FxRateService fxRateService;

    // 2026-05-26 Sprint 3 S6：outbox eventType 不含 "falconx." 前缀，
    // KafkaTradingOutboxEventPublisher.resolveTopic 会自动加，避免双前缀。
    private static final String POST_PROCESS_EVENT_TYPE = "trading.position.close.post-process";
    // STAGE-8-NOTIFICATION：平仓/强平时落站内信，可选注入。
    private final TradingNotificationApplicationService notificationService;

    public TradingPositionCloseApplicationService(TradingCoreServiceProperties properties,
                                                  TradingPositionRepository tradingPositionRepository,
                                                  TradingTradeRepository tradingTradeRepository,
                                                  TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository,
                                                  TradingAccountService tradingAccountService,
                                                  TradingRiskObservabilityService tradingRiskObservabilityService,
                                                  TradingOutboxRepository tradingOutboxRepository,
                                                  TradingLiquidationLogRepository tradingLiquidationLogRepository,
                                                  TradingScheduleService tradingScheduleService,
                                                  OpenPositionSnapshotStore openPositionSnapshotStore,
                                                  PositionTriggerRuleEvaluator positionTriggerRuleEvaluator,
                                                  TradingUserRealtimePushService tradingUserRealtimePushService,
                                                  @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                  com.falconx.trading.repository.TradingPendingOrderTriggerRepository pendingOrderRepository,
                                                  @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                  TradingNotificationApplicationService notificationService,
                                                  com.falconx.infrastructure.id.IdGenerator idGenerator,
                                                  @org.springframework.beans.factory.annotation.Value("${falconx.trading.position-close.async-post-process.enabled:true}") boolean asyncPostProcessEnabled,
                                                  MarketSymbolSpecRepository marketSymbolSpecRepository,
                                                  FxRateService fxRateService) {
        this.properties = properties;
        this.tradingPositionRepository = tradingPositionRepository;
        this.tradingTradeRepository = tradingTradeRepository;
        this.tradingQuoteSnapshotRepository = tradingQuoteSnapshotRepository;
        this.tradingAccountService = tradingAccountService;
        this.tradingRiskObservabilityService = tradingRiskObservabilityService;
        this.tradingOutboxRepository = tradingOutboxRepository;
        this.tradingLiquidationLogRepository = tradingLiquidationLogRepository;
        this.tradingScheduleService = tradingScheduleService;
        this.openPositionSnapshotStore = openPositionSnapshotStore;
        this.positionTriggerRuleEvaluator = positionTriggerRuleEvaluator;
        this.tradingUserRealtimePushService = tradingUserRealtimePushService;
        this.pendingOrderRepository = pendingOrderRepository;
        this.notificationService = notificationService;
        this.idGenerator = idGenerator;
        this.asyncPostProcessEnabled = asyncPostProcessEnabled;
        this.marketSymbolSpecRepository = marketSymbolSpecRepository;
        this.fxRateService = fxRateService;
    }

    /**
     * 执行手动平仓。
     */
    @Transactional
    public PositionCloseResult closePosition(CloseTradingPositionCommand command) {
        OffsetDateTime now = OffsetDateTime.now();
        TradingPosition position = tradingPositionRepository.findByIdAndUserIdForUpdate(command.positionId(), command.userId())
                .orElseThrow(() -> new TradingBusinessException(
                        TradingErrorCode.POSITION_NOT_FOUND,
                        Map.of(
                                "userId", command.userId(),
                                "positionId", command.positionId(),
                                "rejectionReason", TradingErrorCode.POSITION_NOT_FOUND.name()
                        )
                ));
        if (position.isTerminal()) {
            throw new TradingBusinessException(
                    TradingErrorCode.POSITION_ALREADY_CLOSED,
                    Map.of(
                            "userId", command.userId(),
                            "positionId", command.positionId(),
                            "symbol", position.symbol(),
                            "rejectionReason", TradingErrorCode.POSITION_ALREADY_CLOSED.name()
                    )
            );
        }
        if (!tradingScheduleService.isCloseAllowed(position.symbol(), now)) {
            throw new TradingBusinessException(
                    TradingErrorCode.SYMBOL_TRADING_SUSPENDED,
                    Map.of(
                            "userId", command.userId(),
                            "positionId", command.positionId(),
                            "symbol", position.symbol(),
                            "rejectionReason", TradingErrorCode.SYMBOL_TRADING_SUSPENDED.name()
                    )
            );
        }
        TradingQuoteSnapshot quote = requireQuoteForManualClose(position);
        return settlePositionExit(position, quote, TradingPositionCloseReason.MANUAL, now);
    }

    /**
     * 执行 TP/SL / 强平退出。
     *
     * <p>该入口只服务系统内部触发路径，不走 HTTP owner 校验。
     *
     * @param positionId 持仓 ID
     * @param closeReason 触发原因，仅允许 `TAKE_PROFIT / STOP_LOSS / LIQUIDATION`
     * @param quote 当前有效报价
     * @return 退出结果；若持仓已被其他并发链路关闭，返回 `null`
     */
    @Transactional
    public PositionCloseResult closePositionByTrigger(Long positionId,
                                                      TradingPositionCloseReason closeReason,
                                                      TradingQuoteSnapshot quote) {
        if (closeReason == null || closeReason == TradingPositionCloseReason.MANUAL) {
            throw new IllegalArgumentException("Triggered close requires TAKE_PROFIT / STOP_LOSS / LIQUIDATION");
        }
        TradingPosition position = tradingPositionRepository.findByIdForUpdate(positionId)
                .orElseThrow(() -> new IllegalStateException("Open position snapshot exists but DB record is missing, positionId=" + positionId));
        if (position.isTerminal()) {
            log.info("trading.position.trigger.skip positionId={} status={} reason={}",
                    positionId,
                    position.status(),
                    closeReason);
            return null;
        }
        if (!tradingScheduleService.isOpenAllowed(position.symbol(), OffsetDateTime.now())) {
            log.info("trading.position.trigger.skip positionId={} symbol={} requestedReason={} reason=MARKET_CLOSED",
                    positionId,
                    position.symbol(),
                    closeReason);
            return null;
        }
        if (quote == null || quote.mark() == null || !quote.executable()) {
            throw new IllegalStateException("Triggered close requires an executable quote, positionId=" + positionId);
        }
        // STAGE-12-GROUP-MARKUP: 用 position 冻结值算 effectiveMark
        // 平仓判定与 entryPrice 含 markup 口径对齐
        BigDecimal effectiveMarkPrice = TradingPricingSupport.resolvePositionMarkPrice(quote, position);
        if (effectiveMarkPrice == null) {
            throw new IllegalStateException("Triggered close requires executable bid/ask quote, positionId=" + positionId);
        }
        // STAGE-14D2 Task 1：CROSS_STOP_OUT 是账户级强平触发（marginLevel ≤ stopOut，由
        //   CrossLiquidationOrchestrator 决策，单仓 liquidationPrice=null），不存在单仓价格门控可二次校验。
        //   PositionTriggerRuleEvaluator.evaluate 对 liqPrice=null 的 CROSS 仓必返回 null → 会被静默吞掉
        //   账户级强平。故此处跳过二次价格校验直接落账（FOR UPDATE + isTerminal() 仍兜底防重复平）。
        //   其余 reason（LIQUIDATION/SL/TP）保持既有二次校验，C1 ISOLATED 行为零影响。
        if (closeReason == TradingPositionCloseReason.CROSS_STOP_OUT) {
            return settlePositionExit(position, quote, closeReason, OffsetDateTime.now());
        }
        TradingPositionCloseReason effectiveCloseReason = positionTriggerRuleEvaluator.evaluate(position, effectiveMarkPrice);
        if (effectiveCloseReason == null) {
            log.info("trading.position.trigger.skip.revalidated positionId={} requestedReason={} reason=CONDITION_NOT_MET currentTakeProfitPrice={} currentStopLossPrice={} currentLiquidationPrice={} markPrice={}",
                    positionId,
                    closeReason,
                    position.takeProfitPrice(),
                    position.stopLossPrice(),
                    position.liquidationPrice(),
                    effectiveMarkPrice);
            return null;
        }
        if (effectiveCloseReason != closeReason) {
            log.info("trading.position.trigger.revalidated positionId={} requestedReason={} effectiveReason={} currentTakeProfitPrice={} currentStopLossPrice={} currentLiquidationPrice={} markPrice={}",
                    positionId,
                    closeReason,
                    effectiveCloseReason,
                    position.takeProfitPrice(),
                    position.stopLossPrice(),
                    position.liquidationPrice(),
                    effectiveMarkPrice);
        }
        return settlePositionExit(position, quote, effectiveCloseReason, OffsetDateTime.now());
    }

    /**
     * STAGE-2-TRADING-MONITOR R4：管理员手动强平。
     *
     * <p>与 {@link #closePositionByTrigger} 的区别：本入口不重新调用 PositionTriggerRuleEvaluator，
     * 即使当前价未触及 liquidation 线也会立即强平；与自动强平共用 FOR UPDATE 行锁 + 共用
     * settlement 路径（platformCoveredLoss / t_liquidation_log）。
     *
     * @param positionId 持仓 ID
     * @return 退出结果；持仓不存在抛 90650，已平仓抛 90651
     */
    @Transactional
    public PositionCloseResult forceLiquidatePositionByAdmin(Long positionId) {
        TradingPosition position = tradingPositionRepository.findByIdForUpdate(positionId)
                .orElseThrow(() -> new TradingBusinessException(
                        TradingErrorCode.ADMIN_TRADING_POSITION_NOT_FOUND,
                        Map.of("positionId", positionId)
                ));
        if (position.isTerminal()) {
            throw new TradingBusinessException(
                    TradingErrorCode.ADMIN_TRADING_POSITION_ALREADY_CLOSED,
                    Map.of("positionId", positionId, "status", position.status())
            );
        }
        TradingQuoteSnapshot quote = requireQuoteForManualClose(position);
        return settlePositionExit(position, quote, TradingPositionCloseReason.LIQUIDATION, OffsetDateTime.now());
    }

    private PositionCloseResult settlePositionExit(TradingPosition position,
                                                   TradingQuoteSnapshot quote,
                                                   TradingPositionCloseReason closeReason,
                                                   OffsetDateTime occurredAt) {
        // STAGE-12-GROUP-MARKUP: 平仓 exitPrice 用 position 冻结的 markup
        // BUY 持仓平 → bid + bidExtraAtOpen；SELL 持仓平 → ask + askExtraAtOpen
        // realized PnL = (exit - entry) × qty × dir，entry 已含开仓时 markup，exit 含 markup 后 PnL 才正确
        BigDecimal effectiveMarkPrice = TradingPricingSupport.resolvePositionMarkPrice(quote, position);
        if (effectiveMarkPrice == null) {
            throw new TradingBusinessException(TradingErrorCode.QUOTE_NOT_AVAILABLE);
        }
        // realizedPnl 为原币（quote currency）已实现盈亏，effectiveMarkPrice 已含 STAGE-12 markup。
        BigDecimal realizedPnlInQuote = calculateRealizedPnl(position, effectiveMarkPrice);
        TradingAccount settlementAccount = tradingAccountService.getExistingAccountForUpdate(
                position.userId(),
                properties.getSettlementToken()
        );

        // STAGE-14B Task 9b：原币 realizedPnl(QC) → 账户币 realizedPnl(AC) 换算 + 落账三列留痕。
        //   master §3.2：RealizedPnL(AC) = RealizedPnL(QC) × fxAtClose(QC→AC) 当时快照。
        //   balance / 负净值保护 / platformCoveredLoss 一律按账户币 inAccount；t_ledger 三列留原币 (inQuote, QC, fxAtClose)。
        RealizedPnlConversion conversion = convertRealizedPnl(position, settlementAccount, realizedPnlInQuote);
        BigDecimal realizedPnlInAccount = conversion.inAccount();

        // STAGE-14D2 Task 1：CROSS_STOP_OUT 与 ISOLATED LIQUIDATION 同口径落账——
        //   LIQUIDATED 状态 + LIQUIDATION trade type + biz_type=9（LIQUIDATION_PNL）+ t_liquidation_log。
        boolean liquidation = closeReason == TradingPositionCloseReason.LIQUIDATION
                || closeReason == TradingPositionCloseReason.CROSS_STOP_OUT;
        TradingAccountService.PositionSettlementResult settlement = tradingAccountService.settlePositionExit(
                settlementAccount,
                position.margin(),
                realizedPnlInAccount,
                realizedPnlInQuote,
                conversion.originalCurrency(),
                conversion.fxRate(),
                liquidation ? TradingLedgerBizType.LIQUIDATION_PNL : TradingLedgerBizType.REALIZED_PNL,
                liquidation,
                "position-exit:" + position.positionId() + ":" + closeReason.name(),
                String.valueOf(position.positionId()),
                occurredAt
        );

        TradingPositionStatus nextStatus = liquidation ? TradingPositionStatus.LIQUIDATED : TradingPositionStatus.CLOSED;
        TradingTradeType tradeType = liquidation ? TradingTradeType.LIQUIDATION : TradingTradeType.CLOSE;
        // B 阶段：t_position / t_trade.realizedPnl 展示字段与 balance 同口径用账户币 inAccount
        // （WS payload 字段名/结构不变，值用 inAccount）。
        BigDecimal realizedPnl = realizedPnlInAccount;
        TradingPosition exitedPosition = tradingPositionRepository.save(position.close(
                nextStatus,
                closeReason,
                effectiveMarkPrice,
                realizedPnl,
                occurredAt
        ));

        tradingRiskObservabilityService.applyClosePosition(
                position.symbol(),
                position.side(),
                position.quantity(),
                quote,
                occurredAt,
                closeReason,
                position.positionId()
        );

        // 2026-05-26 Sprint 3 S6：主事务内 reserve tradeId，构造 in-memory trade。
        // - 异步路径：trade / liquidation_log / sl_tp 级联撤都通过 outbox post-process 异步落盘
        // - 同步回退路径：与原逻辑一致，全部在主事务内
        long reservedTradeId = idGenerator.nextId();
        TradingTrade inMemoryTrade = new TradingTrade(
                reservedTradeId,
                position.openingOrderId(),
                position.positionId(),
                position.userId(),
                position.symbol(),
                position.side(),
                tradeType,
                position.quantity(),
                effectiveMarkPrice,
                BigDecimal.ZERO.setScale(8),
                realizedPnl,
                occurredAt
        );

        TradingLiquidationLog liquidationLog = null;
        TradingTrade trade;

        if (asyncPostProcessEnabled) {
            // ===== 异步路径（S6 改造）=====
            // 主事务里只写 outbox：主事件 + post-process（trade/liquidation_log/级联撤）
            // 消费者订阅 post-process topic 异步写 DB。
            tradingOutboxRepository.save(liquidation
                    ? buildLiquidationOutbox(exitedPosition, inMemoryTrade, settlement, null, occurredAt)
                    : buildPositionClosedOutbox(exitedPosition, inMemoryTrade, occurredAt));

            tradingOutboxRepository.save(buildTradeWriteOutbox(reservedTradeId, exitedPosition,
                    inMemoryTrade, occurredAt));

            if (liquidation) {
                tradingOutboxRepository.save(buildLiquidationLogWriteOutbox(exitedPosition,
                        effectiveMarkPrice, realizedPnl, settlement, quote, occurredAt));
            }

            if (pendingOrderRepository != null) {
                tradingOutboxRepository.save(buildPendingOrderCascadeCancelOutbox(
                        exitedPosition, closeReason, occurredAt));
            }

            trade = inMemoryTrade;
        } else {
            // ===== 同步回退路径（feature flag = false）=====
            trade = tradingTradeRepository.save(new TradingTrade(
                    null,
                    position.openingOrderId(),
                    position.positionId(),
                    position.userId(),
                    position.symbol(),
                    position.side(),
                    tradeType,
                    position.quantity(),
                    effectiveMarkPrice,
                    BigDecimal.ZERO.setScale(8),
                    realizedPnl,
                    occurredAt
            ));

            if (liquidation) {
                liquidationLog = tradingLiquidationLogRepository.save(new TradingLiquidationLog(
                        null,
                        position.userId(),
                        position.positionId(),
                        position.symbol(),
                        position.side(),
                        position.marginMode(),
                        position.quantity(),
                        position.entryPrice(),
                        position.liquidationPrice(),
                        effectiveMarkPrice,
                        quote.ts(),
                        quote.source(),
                        realizedPnl.signum() < 0 ? realizedPnl.abs() : BigDecimal.ZERO.setScale(8),
                        BigDecimal.ZERO.setScale(8),
                        position.margin(),
                        settlement.platformCoveredLoss(),
                        occurredAt
                ));
            }

            tradingOutboxRepository.save(liquidation
                    ? buildLiquidationOutbox(exitedPosition, trade, settlement, liquidationLog, occurredAt)
                    : buildPositionClosedOutbox(exitedPosition, trade, occurredAt));

            if (pendingOrderRepository != null) {
                try {
                    int cancelled = pendingOrderRepository.cancelAllSlTpByPositionId(
                            position.positionId(), "PARENT_POSITION_CLOSED_BY_" + closeReason.name());
                    if (cancelled > 0) {
                        log.info("trading.pending-order.sl-tp.parent-closed positionId={} cancelled={}",
                                position.positionId(), cancelled);
                    }
                } catch (RuntimeException ex) {
                    log.warn("trading.pending-order.sl-tp.cascade-failed positionId={} message={}",
                            position.positionId(), ex.getMessage());
                }
            }
        }

        registerSnapshotRemoval(exitedPosition, trade, settlement.account(), liquidationLog);
        if (liquidation && liquidationLog != null) {
            log.warn("trading.liquidation.executed userId={} positionId={} liquidationLogId={} closePrice={} realizedPnl={} platformCoveredLoss={} quoteTs={} quoteSource={}",
                    position.userId(),
                    position.positionId(),
                    liquidationLog.liquidationLogId(),
                    effectiveMarkPrice,
                    realizedPnl,
                    settlement.platformCoveredLoss(),
                    quote.ts(),
                    quote.source());
        }
        log.info("trading.position.exit.completed userId={} positionId={} reason={} status={} closePrice={} realizedPnl={} appliedPnl={} platformCoveredLoss={} quoteSource={} quoteTs={}",
                position.userId(),
                position.positionId(),
                closeReason,
                exitedPosition.status(),
                effectiveMarkPrice,
                realizedPnl,
                settlement.appliedPnl(),
                settlement.platformCoveredLoss(),
                quote.source(),
                quote.ts());
        return new PositionCloseResult(exitedPosition, trade, settlement.account(), liquidationLog);
    }

    private TradingQuoteSnapshot requireQuoteForManualClose(TradingPosition position) {
        TradingQuoteSnapshot quote = tradingQuoteSnapshotRepository.findBySymbol(position.symbol())
                .orElseThrow(() -> new TradingBusinessException(
                        TradingErrorCode.QUOTE_NOT_AVAILABLE,
                        Map.of(
                                "userId", position.userId(),
                                "positionId", position.positionId(),
                                "symbol", position.symbol(),
                                "rejectionReason", TradingErrorCode.QUOTE_NOT_AVAILABLE.name()
                        )
                ));
        if (quote.qualityStatus() == com.falconx.trading.entity.TradingQuoteQualityStatus.NO_QUOTE) {
            throw new TradingBusinessException(
                    TradingErrorCode.QUOTE_NOT_AVAILABLE,
                    Map.of(
                            "userId", position.userId(),
                            "positionId", position.positionId(),
                            "symbol", position.symbol(),
                            "rejectionReason", TradingErrorCode.QUOTE_NOT_AVAILABLE.name()
                    )
            );
        }
        if (!quote.executable()) {
            throw new TradingBusinessException(
                    TradingErrorCode.PRICE_SOURCE_STALE_OR_DISCONNECTED,
                    Map.of(
                            "userId", position.userId(),
                            "positionId", position.positionId(),
                            "symbol", position.symbol(),
                            "rejectionReason", TradingErrorCode.PRICE_SOURCE_STALE_OR_DISCONNECTED.name()
                    )
            );
        }
        if (quote.bid() == null || quote.ask() == null) {
            throw new TradingBusinessException(
                    TradingErrorCode.QUOTE_NOT_AVAILABLE,
                    Map.of(
                            "userId", position.userId(),
                            "positionId", position.positionId(),
                            "symbol", position.symbol(),
                            "rejectionReason", TradingErrorCode.QUOTE_NOT_AVAILABLE.name()
                    )
            );
        }
        return quote;
    }

    private TradingOutboxMessage buildPositionClosedOutbox(TradingPosition position,
                                                           TradingTrade trade,
                                                           OffsetDateTime occurredAt) {
        return new TradingOutboxMessage(
                null,
                "position-closed:" + position.positionId(),
                "trading.position.closed",
                String.valueOf(position.userId()),
                Map.of(
                        "positionId", position.positionId(),
                        "userId", position.userId(),
                        "symbol", position.symbol(),
                        "side", position.side().name(),
                        "tradeId", trade.tradeId(),
                        "closePrice", position.closePrice(),
                        "closeReason", position.closeReason().name(),
                        "realizedPnl", position.realizedPnl(),
                        "closedAt", position.closedAt()
                ),
                TradingOutboxStatus.PENDING,
                occurredAt,
                null,
                0,
                occurredAt,
                null
        );
    }

    private TradingOutboxMessage buildLiquidationOutbox(TradingPosition position,
                                                        TradingTrade trade,
                                                        TradingAccountService.PositionSettlementResult settlement,
                                                        TradingLiquidationLog liquidationLog,
                                                        OffsetDateTime occurredAt) {
        // 2026-05-26 Sprint 3 S6：异步路径下 liquidationLog 在 outbox post-process 写，
        // 此时主事件中 liquidationLogId 字段缺失（消费者按 positionId 关联）。
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("positionId", position.positionId());
        payload.put("userId", position.userId());
        payload.put("symbol", position.symbol());
        payload.put("side", position.side().name());
        payload.put("tradeId", trade.tradeId());
        if (liquidationLog != null) {
            payload.put("liquidationLogId", liquidationLog.liquidationLogId());
        }
        payload.put("closePrice", position.closePrice());
        payload.put("closeReason", position.closeReason().name());
        payload.put("realizedPnl", position.realizedPnl());
        payload.put("appliedPnl", settlement.appliedPnl());
        payload.put("platformCoveredLoss", settlement.platformCoveredLoss());
        payload.put("closedAt", position.closedAt());
        return new TradingOutboxMessage(
                null,
                "liquidation-executed:" + position.positionId(),
                "trading.liquidation.executed",
                String.valueOf(position.userId()),
                payload,
                TradingOutboxStatus.PENDING,
                occurredAt,
                null,
                0,
                occurredAt,
                null
        );
    }

    private TradingOutboxMessage buildTradeWriteOutbox(long tradeId,
                                                       TradingPosition position,
                                                       TradingTrade trade,
                                                       OffsetDateTime occurredAt) {
        return new TradingOutboxMessage(
                null,
                "trade-write:" + position.positionId() + ":" + tradeId,
                POST_PROCESS_EVENT_TYPE,
                String.valueOf(position.userId()),
                Map.ofEntries(
                        Map.entry("eventType", "TRADE_WRITE"),
                        Map.entry("tradeId", tradeId),
                        Map.entry("orderId", trade.orderId()),
                        Map.entry("positionId", position.positionId()),
                        Map.entry("userId", position.userId()),
                        Map.entry("symbol", position.symbol()),
                        Map.entry("side", position.side().name()),
                        Map.entry("tradeType", trade.tradeType().name()),
                        Map.entry("quantity", trade.quantity()),
                        Map.entry("price", trade.price()),
                        Map.entry("fee", trade.fee()),
                        Map.entry("realizedPnl", trade.realizedPnl()),
                        Map.entry("tradedAt", trade.tradedAt())
                ),
                TradingOutboxStatus.PENDING,
                occurredAt,
                null,
                0,
                occurredAt,
                null
        );
    }

    private TradingOutboxMessage buildLiquidationLogWriteOutbox(TradingPosition position,
                                                                 BigDecimal effectiveMarkPrice,
                                                                 BigDecimal realizedPnl,
                                                                 TradingAccountService.PositionSettlementResult settlement,
                                                                 TradingQuoteSnapshot quote,
                                                                 OffsetDateTime occurredAt) {
        // STAGE-14D2 Task 1：CROSS 强平仓 liquidationPrice=null（账户级触发，单仓无强平价），
        //   Map.ofEntries 不允许 null value → 改用 LinkedHashMap（与 buildLiquidationOutbox 同口径），
        //   liquidationPrice 为 null 时如实置 null（消费者按 null 落 t_liquidation_log.liquidation_price）。
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("eventType", "LIQUIDATION_LOG_WRITE");
        payload.put("userId", position.userId());
        payload.put("positionId", position.positionId());
        payload.put("symbol", position.symbol());
        payload.put("side", position.side().name());
        payload.put("marginMode", position.marginMode().name());
        payload.put("quantity", position.quantity());
        payload.put("entryPrice", position.entryPrice());
        payload.put("liquidationPrice", position.liquidationPrice());
        payload.put("closePrice", effectiveMarkPrice);
        payload.put("quoteTs", quote.ts());
        payload.put("quoteSource", quote.source());
        payload.put("netLossAfterMargin", realizedPnl.signum() < 0 ? realizedPnl.abs() : BigDecimal.ZERO.setScale(8));
        payload.put("liquidationFee", BigDecimal.ZERO.setScale(8));
        payload.put("marginReleased", position.margin());
        payload.put("platformCoveredLoss", settlement.platformCoveredLoss());
        payload.put("occurredAt", occurredAt);
        return new TradingOutboxMessage(
                null,
                "liquidation-log-write:" + position.positionId(),
                POST_PROCESS_EVENT_TYPE,
                String.valueOf(position.userId()),
                payload,
                TradingOutboxStatus.PENDING,
                occurredAt,
                null,
                0,
                occurredAt,
                null
        );
    }

    private TradingOutboxMessage buildPendingOrderCascadeCancelOutbox(TradingPosition position,
                                                                       TradingPositionCloseReason closeReason,
                                                                       OffsetDateTime occurredAt) {
        return new TradingOutboxMessage(
                null,
                "pending-order-cascade-cancel:" + position.positionId(),
                POST_PROCESS_EVENT_TYPE,
                String.valueOf(position.userId()),
                Map.of(
                        "eventType", "PENDING_ORDER_CASCADE_CANCEL",
                        "positionId", position.positionId(),
                        "closeReason", closeReason.name()
                ),
                TradingOutboxStatus.PENDING,
                occurredAt,
                null,
                0,
                occurredAt,
                null
        );
    }

    private void registerSnapshotRemoval(TradingPosition position,
                                         TradingTrade trade,
                                         TradingAccount account,
                                         TradingLiquidationLog liquidationLog) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                openPositionSnapshotStore.remove(position.symbol(), position.positionId());
                tradingUserRealtimePushService.publishPositionClosed(position, trade, account, liquidationLog);
                publishPositionNotification(position, trade);
            }
        });
    }

    private void publishPositionNotification(TradingPosition position, TradingTrade trade) {
        if (notificationService == null) return;
        TradingPositionCloseReason reason = position.closeReason();
        String sideLabel = position.side() == com.falconx.trading.entity.TradingOrderSide.BUY ? "多" : "空";
        java.math.BigDecimal pnl = position.realizedPnl();
        String pnlSign = pnl == null ? "" : (pnl.signum() >= 0 ? "+" : "");
        String pnlStr = pnl == null ? "—" : pnl.toPlainString();
        String closePrice = trade.price() == null ? "—" : trade.price().toPlainString();
        String templateCode;
        if (reason == TradingPositionCloseReason.LIQUIDATION) {
            templateCode = "POSITION_LIQUIDATED";
        } else if (reason == TradingPositionCloseReason.CROSS_STOP_OUT) {
            // STAGE-14D2 Task 5：CROSS_STOP_OUT 是账户级强平——逐仓 close 不再发逐仓 POSITION_LIQUIDATED
            //   站内信（避免每平一仓发一条，重复打扰）；改由 CrossLiquidationOrchestrator 强平完成后
            //   发一条账户级 CROSS_STOP_OUT_TRIGGERED 汇总通知（含 marginLevel + 强平仓位数 + symbol 清单）。
            //   ISOLATED LIQUIDATION（上一分支）逐仓通知不受影响。
            return;
        } else if (reason == TradingPositionCloseReason.TAKE_PROFIT) {
            templateCode = "POSITION_TP_HIT";
        } else if (reason == TradingPositionCloseReason.STOP_LOSS) {
            templateCode = "POSITION_SL_HIT";
        } else {
            // 手动平仓走 OrderTicket / ClosePositionModal 内的 toast，不重复站内信。
            return;
        }
        try {
            notificationService.send(
                    templateCode,
                    position.userId(),
                    templateCode,
                    java.util.Map.of(
                            "symbol", position.symbol(),
                            "side", sideLabel,
                            "closePrice", closePrice,
                            "pnl", pnlSign + pnlStr
                    ),
                    "POSITION",
                    position.positionId(),
                    null
            );
        } catch (RuntimeException ex) {
            log.warn("trading.position.notification.send.failed positionId={} reason={}",
                    position.positionId(), ex.toString());
        }
    }

    /**
     * STAGE-12-GROUP-MARKUP：caller 传入的 {@code effectiveMarkPrice} 已经走过
     * {@link TradingPricingSupport#resolvePositionMarkPrice(TradingQuoteSnapshot, TradingPosition)}，
     * 含 position 冻结的 markup。本方法不再调 {@code calculatePositionPnl}（它会再加一次 markup，
     * 适合 caller 传基准价的 WS / admin monitor 链路），直接做 (effective - entry) × qty × dir。
     */
    private BigDecimal calculateRealizedPnl(TradingPosition position, BigDecimal effectiveMarkPrice) {
        BigDecimal delta = position.side() == com.falconx.trading.entity.TradingOrderSide.BUY
                ? effectiveMarkPrice.subtract(position.entryPrice())
                : position.entryPrice().subtract(effectiveMarkPrice);
        return TradingPricingSupport.scaleAmount(delta.multiply(position.quantity()));
    }

    /**
     * STAGE-14B Task 9b：把原币 RealizedPnL(QC) 换算成账户币 RealizedPnL(AC)，并解析三列留痕用的
     * {@code originalCurrency} / {@code fxAtClose}（master §3.2）。
     *
     * <p><b>降级不阻断</b>（master §3.5.5）：平仓尤其强平绝不能因 FX 卡住。下列任一情况退化为
     * 账户币原值（inAccount = realizedPnl(QC) 原值、fxAtClose = ONE）+ warn 日志，继续完成平仓 / 强平：
     * <ul>
     *   <li>SymbolSpec 缺失或过渡期 quoteCurrency 未回填（null/blank）→ 此时确实不知道 QC，
     *       originalCurrency 只能记 <b>账户币</b>，不能瞎写。</li>
     *   <li>FX rate 内存不可用（quoteCurrency 已知但查不到 rate）→ QC 是确定的，
     *       originalCurrency 如实记 <b>quoteCurrency</b>（原币），仅 fxAtClose=ONE 表示降级未真实换算，
     *       避免「原币值却标账户币」的审计误导（STAGE-14B Task 9b 收口 Issue 2）。</li>
     * </ul>
     * 同币种短路：fxAtClose = ONE、inAccount == inQuote，不查询。负数 realizedPnl 换算 HALF_UP 保持正确符号。
     */
    private RealizedPnlConversion convertRealizedPnl(TradingPosition position,
                                                     TradingAccount settlementAccount,
                                                     BigDecimal realizedPnlInQuote) {
        String accountCurrency = settlementAccount.currency();
        String quoteCurrency = marketSymbolSpecRepository.findByPlatformSymbol(position.symbol())
                .map(SymbolSpec::quoteCurrency)
                .orElse(null);
        if (quoteCurrency == null || quoteCurrency.isBlank()) {
            log.warn("trading.position.exit.fx.degraded positionId={} symbol={} reason=QUOTE_CURRENCY_MISSING realizedPnl={} accountCurrency={}",
                    position.positionId(), position.symbol(), realizedPnlInQuote, accountCurrency);
            return new RealizedPnlConversion(realizedPnlInQuote, BigDecimal.ONE, accountCurrency);
        }
        if (quoteCurrency.equals(accountCurrency)) {
            return new RealizedPnlConversion(realizedPnlInQuote, BigDecimal.ONE, quoteCurrency);
        }
        BigDecimal fxRate = fxRateService.queryRate(quoteCurrency, accountCurrency).orElse(null);
        if (fxRate == null) {
            log.warn("trading.position.exit.fx.degraded positionId={} symbol={} reason=FX_RATE_UNAVAILABLE quoteCurrency={} accountCurrency={} realizedPnl={}",
                    position.positionId(), position.symbol(), quoteCurrency, accountCurrency, realizedPnlInQuote);
            // STAGE-14B Task 9b 收口 Issue 2：QC 已知仅 rate 查不到 → originalCurrency 如实记原币 quoteCurrency，
            //   inAccount 退化为原币原值、fxAtClose=ONE 表示未真实换算（避免原币值标账户币的审计误导）。
            return new RealizedPnlConversion(realizedPnlInQuote, BigDecimal.ONE, quoteCurrency);
        }
        BigDecimal inAccount = realizedPnlInQuote.multiply(fxRate).setScale(8, java.math.RoundingMode.HALF_UP);
        return new RealizedPnlConversion(inAccount, fxRate, quoteCurrency);
    }

    /**
     * STAGE-14B Task 9b：平仓 / 强平 realized PnL 货币换算结果。
     *
     * @param inAccount         账户币 RealizedPnL(AC)，驱动 balance / 负净值保护
     * @param fxRate            fxAtClose(QC→AC)，落 t_ledger.fx_rate_at_settlement
     * @param originalCurrency  原币代码（计价币 QC）；FX 不可用降级仍记真实 quoteCurrency，仅 SymbolSpec 缺失 / QC 未回填降级时记账户币，落 t_ledger.original_currency
     */
    private record RealizedPnlConversion(BigDecimal inAccount, BigDecimal fxRate, String originalCurrency) {
    }
}
