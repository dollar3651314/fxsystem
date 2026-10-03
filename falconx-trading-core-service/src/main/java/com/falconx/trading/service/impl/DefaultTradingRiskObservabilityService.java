package com.falconx.trading.service.impl;

import com.falconx.trading.entity.TradingHedgeLog;
import com.falconx.trading.entity.TradingHedgeLogStatus;
import com.falconx.trading.entity.TradingHedgeTriggerSource;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPositionCloseReason;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingRiskConfig;
import com.falconx.trading.entity.TradingRiskControlActionType;
import com.falconx.trading.entity.TradingRiskExposure;
import com.falconx.trading.entity.TradingRiskMarketConfig;
import com.falconx.trading.entity.TradingSymbolCorrelationGroup;
import com.falconx.trading.entity.TradingSymbolCorrelationMember;
import com.falconx.trading.event.TradingHedgeAlertEvent;
import com.falconx.trading.producer.TradingHedgeAlertEventPublisher;
import com.falconx.trading.repository.TradingHedgeLogRepository;
import com.falconx.trading.repository.TradingRiskConfigRepository;
import com.falconx.trading.repository.TradingRiskControlActionRepository;
import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingRiskExposureRepository;
import com.falconx.trading.service.FxRateService;
import com.falconx.trading.repository.TradingRiskMarketConfigRepository;
import com.falconx.trading.repository.TradingSymbolCorrelationGroupRepository;
import com.falconx.trading.service.TradingRiskObservabilityService;
import com.falconx.trading.support.TradingPricingSupport;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B-book 风险可观测性默认实现。
 *
 * <p>该实现把数量敞口、美元敞口、阈值判断、{@code t_hedge_log} 追踪、风控动作激活/停用
 * 和跨品种集中度保护收敛到同一处，避免多条触发链路各自维护分叉逻辑。
 */
@Service
public class DefaultTradingRiskObservabilityService implements TradingRiskObservabilityService {

    private static final Logger log = LoggerFactory.getLogger(DefaultTradingRiskObservabilityService.class);

    /** AUTO 触发来源标识（单品种敞口阈值超标）。 */
    private static final String TRIGGER_SOURCE_AUTO = "AUTO";
    private static final String TRIGGER_SOURCE_AUTO_IMBALANCE = "AUTO_DIRECTION_IMBALANCE";
    private static final String TRIGGER_SOURCE_AUTO_PLATFORM = "AUTO_PLATFORM_EXPOSURE";
    /** AUTO_CONCENTRATION 触发来源标识（跨品种集中度超标）。 */
    private static final String TRIGGER_SOURCE_AUTO_CONCENTRATION = "AUTO_CONCENTRATION";
    /** STAGE-9-RISK-OPS §12.1：相关性组组合敞口超标触发来源。 */
    private static final String TRIGGER_SOURCE_AUTO_CORRELATION = "AUTO_CORRELATION";

    private final TradingRiskExposureRepository tradingRiskExposureRepository;
    private final TradingRiskConfigRepository tradingRiskConfigRepository;
    private final TradingHedgeLogRepository tradingHedgeLogRepository;
    private final TradingRiskObservabilityDecider tradingRiskObservabilityDecider;
    private final TradingHedgeAlertEventPublisher tradingHedgeAlertEventPublisher;
    private final TradingRiskControlActionRepository tradingRiskControlActionRepository;
    private final TradingRiskMarketConfigRepository tradingRiskMarketConfigRepository;
    private final TradingSymbolCorrelationGroupRepository tradingSymbolCorrelationGroupRepository;
    private final MarketSymbolSpecRepository marketSymbolSpecRepository;
    private final FxRateService fxRateService;

    public DefaultTradingRiskObservabilityService(TradingRiskExposureRepository tradingRiskExposureRepository,
                                                  TradingRiskConfigRepository tradingRiskConfigRepository,
                                                  TradingHedgeLogRepository tradingHedgeLogRepository,
                                                  TradingRiskObservabilityDecider tradingRiskObservabilityDecider,
                                                  TradingHedgeAlertEventPublisher tradingHedgeAlertEventPublisher,
                                                  TradingRiskControlActionRepository tradingRiskControlActionRepository,
                                                  TradingRiskMarketConfigRepository tradingRiskMarketConfigRepository,
                                                  TradingSymbolCorrelationGroupRepository tradingSymbolCorrelationGroupRepository,
                                                  MarketSymbolSpecRepository marketSymbolSpecRepository,
                                                  FxRateService fxRateService) {
        this.tradingRiskExposureRepository = tradingRiskExposureRepository;
        this.tradingRiskConfigRepository = tradingRiskConfigRepository;
        this.tradingHedgeLogRepository = tradingHedgeLogRepository;
        this.tradingRiskObservabilityDecider = tradingRiskObservabilityDecider;
        this.tradingHedgeAlertEventPublisher = tradingHedgeAlertEventPublisher;
        this.tradingRiskControlActionRepository = tradingRiskControlActionRepository;
        this.tradingRiskMarketConfigRepository = tradingRiskMarketConfigRepository;
        this.tradingSymbolCorrelationGroupRepository = tradingSymbolCorrelationGroupRepository;
        this.marketSymbolSpecRepository = marketSymbolSpecRepository;
        this.fxRateService = fxRateService;
    }

    /**
     * 多币种 USD 化（2026-06-03，§1 下一步 (A)）：解析 symbol 计价币(QC)→USD 汇率。
     * QC 取 SymbolSpec（与双币 PnL 同源）；QC=USD 短路 1；QC 缺失或 FX 不可用降级 1
     * （= 历史口径，风控宁可用近似值持续判定也不中断）并 warn。
     */
    private BigDecimal resolveQcToUsdFxRate(String symbol) {
        String quoteCurrency = marketSymbolSpecRepository.findByPlatformSymbol(symbol)
                .map(SymbolSpec::quoteCurrency)
                .orElse(null);
        if (quoteCurrency == null || quoteCurrency.isBlank()) {
            log.warn("trading.risk.exposure.fx.degraded symbol={} reason=quote_currency_missing fx=1", symbol);
            return BigDecimal.ONE;
        }
        if ("USD".equals(quoteCurrency)) {
            return BigDecimal.ONE;
        }
        return fxRateService.queryRate(quoteCurrency, "USD").orElseGet(() -> {
            log.warn("trading.risk.exposure.fx.degraded symbol={} qc={} reason=fx_unavailable fx=1", symbol, quoteCurrency);
            return BigDecimal.ONE;
        });
    }

    @Override
    @Transactional
    public void applyOpenPosition(String symbol,
                                  TradingOrderSide side,
                                  BigDecimal quantity,
                                  TradingQuoteSnapshot quote,
                                  OffsetDateTime occurredAt,
                                  Long positionId) {
        TradingQuoteSnapshot freshQuote = requireFreshQuote(quote, symbol);
        BigDecimal fxRate = resolveQcToUsdFxRate(symbol);
        tradingRiskExposureRepository.applyOpenPosition(
                symbol,
                side,
                quantity,
                TradingPricingSupport.scaleAmount(freshQuote.bid()),
                TradingPricingSupport.scaleAmount(freshQuote.ask()),
                fxRate,
                occurredAt
        );
        observeThreshold(symbol, freshQuote, fxRate, occurredAt, TradingHedgeTriggerSource.OPEN_POSITION, positionId);
    }

    @Override
    @Transactional
    public void applyClosePosition(String symbol,
                                   TradingOrderSide side,
                                   BigDecimal quantity,
                                   TradingQuoteSnapshot quote,
                                   OffsetDateTime occurredAt,
                                   TradingPositionCloseReason closeReason,
                                   Long positionId) {
        TradingQuoteSnapshot freshQuote = requireFreshQuote(quote, symbol);
        BigDecimal fxRate = resolveQcToUsdFxRate(symbol);
        tradingRiskExposureRepository.applyClosePosition(
                symbol,
                side,
                quantity,
                TradingPricingSupport.scaleAmount(freshQuote.bid()),
                TradingPricingSupport.scaleAmount(freshQuote.ask()),
                fxRate,
                occurredAt
        );
        observeThreshold(symbol, freshQuote, fxRate, occurredAt, resolveTriggerSource(closeReason), positionId);
    }

    @Override
    @Transactional
    public void refreshExposureFromQuote(TradingQuoteSnapshot quote, OffsetDateTime occurredAt) {
        TradingQuoteSnapshot freshQuote = requireFreshQuote(quote, quote == null ? null : quote.symbol());
        BigDecimal fxRate = resolveQcToUsdFxRate(freshQuote.symbol());
        tradingRiskExposureRepository.refreshNetExposureUsd(
                freshQuote.symbol(),
                TradingPricingSupport.scaleAmount(freshQuote.bid()),
                TradingPricingSupport.scaleAmount(freshQuote.ask()),
                fxRate,
                occurredAt
        );
        observeThreshold(freshQuote.symbol(), freshQuote, fxRate, occurredAt, TradingHedgeTriggerSource.PRICE_TICK, null);
    }

    private void observeThreshold(String symbol,
                                  TradingQuoteSnapshot quote,
                                  BigDecimal fxRate,
                                  OffsetDateTime occurredAt,
                                  TradingHedgeTriggerSource triggerSource,
                                  Long positionId) {
        TradingRiskExposure exposure = tradingRiskExposureRepository.findBySymbol(symbol).orElse(null);
        if (exposure == null) {
            return;
        }
        TradingRiskConfig riskConfig = tradingRiskConfigRepository.findBySymbol(symbol).orElse(null);
        if (riskConfig == null || riskConfig.hedgeThresholdUsd() == null || riskConfig.hedgeThresholdUsd().signum() <= 0) {
            return;
        }
        BigDecimal exposureMarkPrice = TradingPricingSupport.resolveExposureMarkPrice(quote, exposure.netExposure());
        if (exposureMarkPrice == null) {
            return;
        }
        TradingHedgeLog latestLog = tradingHedgeLogRepository.findLatestBySymbol(symbol).orElse(null);
        TradingRiskObservabilityDecision decision = tradingRiskObservabilityDecider.evaluate(
                exposure,
                riskConfig.hedgeThresholdUsd(),
                exposureMarkPrice,
                fxRate,
                latestLog
        );

        Long hedgeLogId = null;
        if (decision.shouldWriteHedgeLog()) {
            TradingHedgeLog hedgeLog = tradingHedgeLogRepository.save(new TradingHedgeLog(
                    null,
                    symbol,
                    positionId,
                    triggerSource,
                    decision.actionStatus(),
                    exposure.netExposure(),
                    decision.netExposureUsd(),
                    riskConfig.hedgeThresholdUsd(),
                    exposureMarkPrice,
                    quote.ts(),
                    quote.source(),
                    occurredAt
            ));
            hedgeLogId = hedgeLog.hedgeLogId();

            if (decision.actionStatus() == TradingHedgeLogStatus.ALERT_ONLY) {
                log.warn("trading.risk.hedge.alert symbol={} positionId={} triggerSource={} netExposure={} netExposureUsd={} hedgeThresholdUsd={} markPrice={} priceSource={} quoteTs={} hedgeLogId={}",
                        symbol, positionId, triggerSource,
                        exposure.netExposure(), decision.netExposureUsd(),
                        riskConfig.hedgeThresholdUsd(), exposureMarkPrice,
                        quote.source(), quote.ts(), hedgeLogId);
                tradingHedgeAlertEventPublisher.publishAfterCommit(new TradingHedgeAlertEvent(
                        occurredAt, symbol, decision.netExposureUsd(),
                        riskConfig.hedgeThresholdUsd(), positionId, triggerSource,
                        exposureMarkPrice, quote.ts(), quote.source(), hedgeLogId
                ));
            } else if (decision.actionStatus() == TradingHedgeLogStatus.RECOVERED) {
                log.info("trading.risk.hedge.recovered symbol={} positionId={} triggerSource={} netExposure={} netExposureUsd={} hedgeThresholdUsd={} markPrice={} priceSource={} quoteTs={} hedgeLogId={}",
                        symbol, positionId, triggerSource,
                        exposure.netExposure(), decision.netExposureUsd(),
                        riskConfig.hedgeThresholdUsd(), exposureMarkPrice,
                        quote.source(), quote.ts(), hedgeLogId);
            }
        }

        // 驱动风控动作：敞口超阈值则激活 REJECT_OPEN；恢复则停用。
        if (decision.breached()) {
            boolean activated = tradingRiskControlActionRepository.activateIfAbsent(
                    symbol,
                    TradingRiskControlActionType.REJECT_OPEN,
                    TRIGGER_SOURCE_AUTO,
                    "HEDGE_THRESHOLD_EXCEEDED netExposureUsd=" + decision.netExposureUsd(),
                    hedgeLogId
            );
            if (activated) {
                log.warn("trading.risk.control.activated symbol={} action=REJECT_OPEN source=AUTO netExposureUsd={} hedgeThresholdUsd={}",
                        symbol, decision.netExposureUsd(), riskConfig.hedgeThresholdUsd());
            }
        } else {
            tradingRiskControlActionRepository.deactivate(symbol, TradingRiskControlActionType.REJECT_OPEN, TRIGGER_SOURCE_AUTO);
        }

        // 跨品种集中度保护（市场大类）。
        checkConcentration(symbol, riskConfig.marketCode());

        // BBOOK-RISK-CONTROL-01：方向集中度（多/空 USD 失衡）保护
        checkDirectionImbalance(symbol, riskConfig, exposure, exposureMarkPrice);

        // STAGE-9-RISK-OPS-COMPLETE §12.1：相关性组组合敞口保护
        checkCorrelationGroups(symbol);
    }

    /**
     * STAGE-9-RISK-OPS §12.1：检查 symbol 所属相关性组的组合净敞口。
     *
     * <p>组内任一成员触发：
     * <ol>
     *   <li>查 symbol 所属的所有 enabled=1 组（多组可能）</li>
     *   <li>对每组：累加 |成员.netExposureUsd| × weight（按权重加权聚合）</li>
     *   <li>组合 ≥ thresholdUsd → 对组内所有成员 activateIfAbsent REJECT_OPEN
     *       （trigger_source=AUTO_CORRELATION）</li>
     *   <li>未超阈值 → 对组内所有成员 deactivate（trigger_source=AUTO_CORRELATION，
     *       与单 symbol AUTO / market concentration AUTO_CONCENTRATION 互不影响）</li>
     * </ol>
     *
     * <p>与 {@link #checkConcentration}（market_code 大类）+ {@link #checkDirectionImbalance}
     * （多/空失衡）并行触发；3 类风控触发源在 t_risk_action 表共存，互不覆盖。
     */
    private void checkCorrelationGroups(String symbol) {
        List<TradingSymbolCorrelationGroup> groups =
                tradingSymbolCorrelationGroupRepository.findGroupsBySymbol(symbol);

        Set<String> allMemberSymbols = new LinkedHashSet<>();
        for (TradingSymbolCorrelationGroup group : groups) {
            for (TradingSymbolCorrelationMember member : group.members()) {
                allMemberSymbols.add(member.symbol());
            }
        }

        Map<String, TradingRiskExposure> exposureBySymbol = new HashMap<>();
        if (!allMemberSymbols.isEmpty()) {
            List<String> querySymbols = new ArrayList<>(allMemberSymbols);
            for (TradingRiskExposure exposure : tradingRiskExposureRepository.findBySymbols(querySymbols)) {
                exposureBySymbol.put(exposure.symbol(), exposure);
            }
        }

        for (TradingSymbolCorrelationGroup group : groups) {
            List<String> memberSymbols = new ArrayList<>(group.members().size());
            for (TradingSymbolCorrelationMember m : group.members()) {
                memberSymbols.add(m.symbol());
            }

            BigDecimal totalWeightedUsd = BigDecimal.ZERO;
            for (TradingSymbolCorrelationMember member : group.members()) {
                TradingRiskExposure expo = exposureBySymbol.get(member.symbol());
                if (expo == null || expo.netExposureUsd() == null) continue;
                BigDecimal weighted = expo.netExposureUsd().abs()
                        .multiply(member.weight() == null ? BigDecimal.ONE : member.weight());
                totalWeightedUsd = totalWeightedUsd.add(weighted);
            }
            BigDecimal threshold = group.thresholdUsd();
            if (threshold == null || threshold.signum() <= 0) continue;

            if (totalWeightedUsd.compareTo(threshold) >= 0) {
                for (String memberSymbol : memberSymbols) {
                    boolean activated = tradingRiskControlActionRepository.activateIfAbsent(
                            memberSymbol,
                            TradingRiskControlActionType.REJECT_OPEN,
                            TRIGGER_SOURCE_AUTO_CORRELATION,
                            "CORRELATION_GROUP_EXCEEDED groupCode=" + group.groupCode()
                                    + " totalWeightedUsd=" + totalWeightedUsd
                                    + " thresholdUsd=" + threshold,
                            null
                    );
                    if (activated) {
                        log.warn("trading.risk.control.activated symbol={} action=REJECT_OPEN source=AUTO_CORRELATION groupCode={} totalWeightedUsd={} thresholdUsd={}",
                                memberSymbol, group.groupCode(), totalWeightedUsd, threshold);
                    }
                }
            } else {
                // 未超阈值：批量停用本组 AUTO_CORRELATION 标记
                tradingRiskControlActionRepository.deactivateBatch(
                        memberSymbols,
                        TradingRiskControlActionType.REJECT_OPEN,
                        TRIGGER_SOURCE_AUTO_CORRELATION
                );
            }
        }
    }

    /**
     * BBOOK-RISK-CONTROL-01：方向集中度判定。
     *
     * <p>当 totalLongUsd 或 totalShortUsd 占总敞口的比例超过阈值，且总敞口 ≥ 最小触发额时，
     * 在该 symbol 激活 REJECT_OPEN（trigger_source=AUTO_DIRECTION_IMBALANCE）；
     * 比例回落则停用。
     */
    private void checkDirectionImbalance(String symbol,
                                         com.falconx.trading.entity.TradingRiskConfig riskConfig,
                                         TradingRiskExposure exposure,
                                         BigDecimal markPrice) {
        BigDecimal ratioThreshold = riskConfig.directionImbalanceRatioThreshold();
        if (ratioThreshold == null || ratioThreshold.signum() <= 0) {
            return;
        }
        BigDecimal longUsd = nullSafe(exposure.totalLongQty()).abs().multiply(markPrice);
        BigDecimal shortUsd = nullSafe(exposure.totalShortQty()).abs().multiply(markPrice);
        BigDecimal totalUsd = longUsd.add(shortUsd);
        BigDecimal minTotalUsd = riskConfig.directionImbalanceMinTotalUsd();
        if (minTotalUsd != null && totalUsd.compareTo(minTotalUsd) < 0) {
            // 小盘失衡不触发；同时清理可能存在的历史激活
            tradingRiskControlActionRepository.deactivate(
                    symbol, TradingRiskControlActionType.REJECT_OPEN, TRIGGER_SOURCE_AUTO_IMBALANCE);
            return;
        }
        if (totalUsd.signum() <= 0) {
            return;
        }
        BigDecimal longRatio = longUsd.divide(totalUsd, 6, java.math.RoundingMode.HALF_UP);
        BigDecimal shortRatio = shortUsd.divide(totalUsd, 6, java.math.RoundingMode.HALF_UP);
        BigDecimal maxRatio = longRatio.max(shortRatio);

        if (maxRatio.compareTo(ratioThreshold) >= 0) {
            boolean activated = tradingRiskControlActionRepository.activateIfAbsent(
                    symbol,
                    TradingRiskControlActionType.REJECT_OPEN,
                    TRIGGER_SOURCE_AUTO_IMBALANCE,
                    "DIRECTION_IMBALANCE longUsd=" + longUsd + " shortUsd=" + shortUsd + " ratio=" + maxRatio,
                    null
            );
            if (activated) {
                log.warn("trading.risk.control.activated symbol={} action=REJECT_OPEN source=AUTO_DIRECTION_IMBALANCE longUsd={} shortUsd={} ratio={}",
                        symbol, longUsd, shortUsd, maxRatio);
            }
        } else {
            tradingRiskControlActionRepository.deactivate(
                    symbol, TradingRiskControlActionType.REJECT_OPEN, TRIGGER_SOURCE_AUTO_IMBALANCE);
        }
    }

    private static BigDecimal nullSafe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private void checkConcentration(String symbol, String marketCode) {
        if (marketCode == null) {
            return;
        }
        TradingRiskMarketConfig marketConfig = tradingRiskMarketConfigRepository.findByMarketCode(marketCode).orElse(null);
        if (marketConfig == null || !marketConfig.enabled()) {
            return;
        }
        BigDecimal totalUsd = tradingRiskExposureRepository.sumAbsNetExposureUsdByMarketCode(marketCode);
        List<String> symbols = tradingRiskConfigRepository.findSymbolsByMarketCode(marketCode);

        if (totalUsd.compareTo(marketConfig.concentrationThresholdUsd()) >= 0) {
            for (String sym : symbols) {
                boolean activated = tradingRiskControlActionRepository.activateIfAbsent(
                        sym,
                        TradingRiskControlActionType.REJECT_OPEN,
                        TRIGGER_SOURCE_AUTO_CONCENTRATION,
                        "MARKET_CONCENTRATION_EXCEEDED marketCode=" + marketCode + " totalUsd=" + totalUsd,
                        null
                );
                if (activated) {
                    log.warn("trading.risk.control.activated symbol={} action=REJECT_OPEN source=AUTO_CONCENTRATION marketCode={} totalUsd={}",
                            sym, marketCode, totalUsd);
                }
            }
        } else {
            if (!symbols.isEmpty()) {
                tradingRiskControlActionRepository.deactivateBatch(
                        symbols,
                        TradingRiskControlActionType.REJECT_OPEN,
                        TRIGGER_SOURCE_AUTO_CONCENTRATION
                );
            }
        }
    }

    private TradingQuoteSnapshot requireFreshQuote(TradingQuoteSnapshot quote, String symbol) {
        if (quote == null || quote.bid() == null || quote.ask() == null || quote.stale()) {
            throw new IllegalStateException("Risk observability requires a fresh mark price, symbol=" + symbol);
        }
        return quote;
    }

    private TradingHedgeTriggerSource resolveTriggerSource(TradingPositionCloseReason closeReason) {
        return switch (closeReason) {
            case MANUAL -> TradingHedgeTriggerSource.MANUAL_CLOSE;
            case TAKE_PROFIT -> TradingHedgeTriggerSource.TAKE_PROFIT;
            case STOP_LOSS -> TradingHedgeTriggerSource.STOP_LOSS;
            // STAGE-14D2 Task 1：CROSS 账户级强平的对冲触发源与单仓强平同口径（LIQUIDATION）。
            case LIQUIDATION, CROSS_STOP_OUT -> TradingHedgeTriggerSource.LIQUIDATION;
        };
    }
}
