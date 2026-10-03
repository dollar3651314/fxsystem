package com.falconx.trading.service.impl;

import com.falconx.trading.calculator.LiquidationPriceCalculator;
import com.falconx.trading.calculator.MarginCalculator;
import com.falconx.trading.calculator.MarginResult;
import com.falconx.market.contract.SymbolSpec;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingQuoteQualityStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingRiskConfig;
import com.falconx.trading.entity.TradingRiskControlActionType;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingUserRiskThreshold;
import com.falconx.trading.entity.FxPauseBehavior;
import com.falconx.trading.entity.TradingRiskSwitch;
import com.falconx.trading.repository.FxPauseBehaviorRepository;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.RedisTradingRiskSwitchCache;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.repository.TradingRiskConfigRepository;
import com.falconx.trading.repository.TradingRiskControlActionRepository;
import com.falconx.trading.repository.TradingScheduleSnapshotRepository;
import com.falconx.trading.repository.TradingUserRiskThresholdRepository;
import com.falconx.trading.support.TradingPricingSupport;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.falconx.market.contract.event.MarketGroupMarkupItem;
import com.falconx.trading.service.FxRateService;
import com.falconx.trading.service.LeverageTierResolver;
import com.falconx.trading.service.TradingGroupMarkupService;
import com.falconx.trading.service.TradingRiskService;
import com.falconx.trading.service.TradingScheduleService;
import com.falconx.trading.service.model.LeverageTier;
import com.falconx.trading.service.model.TradingRiskDecision;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 交易风险服务默认实现。
 *
 * <p>该实现承担 Stage 3B 的最小同步风控责任：
 *
 * <ul>
 *   <li>校验品种是否受支持</li>
 *   <li>校验价格是否 stale</li>
 *   <li>校验杠杆与数量是否合法</li>
 *   <li>校验账户可用余额是否足够覆盖保证金与手续费</li>
 *   <li>校验 `t_risk_config` 中的单用户与平台 OPEN 持仓数量上限</li>
 * </ul>
 */
@Service
public class DefaultTradingRiskService implements TradingRiskService {

    private static final Logger log = LoggerFactory.getLogger(DefaultTradingRiskService.class);

    /**
     * B 切片守卫：开仓强平价距离必须 > 点差 × 本倍数，否则拒单 30071
     * LIQUIDATION_DISTANCE_TOO_CLOSE（防 tier 误配致瞬时强平，见 evaluateOpenOrder 注释）。
     */
    private static final java.math.BigDecimal LIQ_DISTANCE_MIN_SPREAD_MULTIPLE = new java.math.BigDecimal("2");

    private final TradingCoreServiceProperties properties;
    private final MarginCalculator marginCalculator;
    private final LiquidationPriceCalculator liquidationPriceCalculator;
    private final TradingScheduleService tradingScheduleService;
    private final TradingScheduleSnapshotRepository tradingScheduleSnapshotRepository;
    private final TradingRiskConfigRepository tradingRiskConfigRepository;
    private final TradingPositionRepository tradingPositionRepository;
    private final TradingRiskControlActionRepository tradingRiskControlActionRepository;
    private final MarketSymbolSpecRepository marketSymbolSpecRepository;
    private final TradingUserRiskThresholdRepository tradingUserRiskThresholdRepository;
    private final TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository;
    private final TradingGroupMarkupService tradingGroupMarkupService;
    private final FxRateService fxRateService;
    private final LeverageTierResolver leverageTierResolver;
    private final FxPauseBehaviorRepository fxPauseBehaviorRepository;
    private final RedisTradingRiskSwitchCache riskSwitchCache;

    public DefaultTradingRiskService(TradingCoreServiceProperties properties,
                                     MarginCalculator marginCalculator,
                                     LiquidationPriceCalculator liquidationPriceCalculator,
                                     TradingScheduleService tradingScheduleService,
                                     TradingScheduleSnapshotRepository tradingScheduleSnapshotRepository,
                                     TradingRiskConfigRepository tradingRiskConfigRepository,
                                     TradingPositionRepository tradingPositionRepository,
                                     TradingRiskControlActionRepository tradingRiskControlActionRepository,
                                     MarketSymbolSpecRepository marketSymbolSpecRepository,
                                     TradingUserRiskThresholdRepository tradingUserRiskThresholdRepository,
                                     TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository,
                                     TradingGroupMarkupService tradingGroupMarkupService,
                                     FxRateService fxRateService,
                                     LeverageTierResolver leverageTierResolver,
                                     FxPauseBehaviorRepository fxPauseBehaviorRepository,
                                     RedisTradingRiskSwitchCache riskSwitchCache) {
        this.properties = properties;
        this.marginCalculator = marginCalculator;
        this.liquidationPriceCalculator = liquidationPriceCalculator;
        this.tradingScheduleService = tradingScheduleService;
        this.tradingScheduleSnapshotRepository = tradingScheduleSnapshotRepository;
        this.tradingRiskConfigRepository = tradingRiskConfigRepository;
        this.tradingPositionRepository = tradingPositionRepository;
        this.tradingRiskControlActionRepository = tradingRiskControlActionRepository;
        this.marketSymbolSpecRepository = marketSymbolSpecRepository;
        this.tradingUserRiskThresholdRepository = tradingUserRiskThresholdRepository;
        this.tradingQuoteSnapshotRepository = tradingQuoteSnapshotRepository;
        this.tradingGroupMarkupService = tradingGroupMarkupService;
        this.fxRateService = fxRateService;
        this.leverageTierResolver = leverageTierResolver;
        this.fxPauseBehaviorRepository = fxPauseBehaviorRepository;
        this.riskSwitchCache = riskSwitchCache;
    }

    @Override
    public TradingRiskDecision evaluateMarketOrder(PlaceMarketOrderCommand command,
                                                   TradingAccount account,
                                                   TradingQuoteSnapshot quote) {
        // 保证金模式 inheritance 规则（STAGE-0-INFRA-EXT-01）：
        //   - 显式传值 → 用请求值
        //   - 不传 → inherit account.marginMode（账户级默认偏好）
        //   - 兜底 → ISOLATED（V11 migration 已让 t_account.margin_mode NOT NULL DEFAULT 2，理论不命中）
        TradingMarginMode marginMode = command.marginMode() != null
                ? command.marginMode()
                : (account.marginMode() != null ? account.marginMode() : TradingMarginMode.ISOLATED);
        // 交易核心不再持有本地静态 symbol 白名单。
        // “平台是否支持该产品”必须以 market-service 预热到 Redis 的交易时间快照为准，
        // 这样产品配置变更后无需同步修改 trading-core 配置，也不会出现两边白名单分叉。
        if (tradingScheduleSnapshotRepository.findBySymbol(command.symbol()).isEmpty()) {
            return reject("SYMBOL_NOT_SUPPORTED");
        }
        if (!tradingScheduleService.isOpenAllowed(command.symbol(), OffsetDateTime.now())) {
            return reject("SYMBOL_TRADING_SUSPENDED");
        }
        if (quote == null) {
            return reject("MARKET_QUOTE_NOT_FOUND");
        }
        if (quote.qualityStatus() == TradingQuoteQualityStatus.NO_QUOTE) {
            return reject("MARKET_QUOTE_NOT_FOUND");
        }
        if (!quote.executable()) {
            return reject("MARKET_QUOTE_STALE");
        }
        if (quote.bid() == null || quote.ask() == null || quote.mark() == null) {
            return reject("MARKET_QUOTE_NOT_FOUND");
        }
        if (command.quantity() == null || command.quantity().signum() <= 0) {
            return reject("INVALID_QUANTITY");
        }
        if (command.leverage() == null || command.leverage().signum() <= 0) {
            return reject("INVALID_LEVERAGE");
        }
        // STAGE-14D2 Task 2：CROSS 开仓放开（gate cross_mode.enabled）。
        //   - ISOLATED：既有路径（单仓 liqPrice + 单仓强平），不变。
        //   - CROSS：cross_mode.enabled 关闭 → reject CROSS_MODE_NOT_ENABLED（30088，与 D1 模式切换 gate 一致，
        //     CROSS 账户级强平由 Task 4 提供，开关默认 false 防止放用户进入无强平保护的 CROSS）；
        //     enabled → 放行走 CROSS 开仓路径（tier/IM/fee 校验、IM 冻结 frozen 同 ISOLATED；
        //     仅强平价置 null，因 master §3.2 CROSS 不存单仓 liqPrice，改账户级 MarginLevel 触发）。
        //   - 其它非 ISOLATED/CROSS 值 → 既有 reject MARGIN_MODE_NOT_SUPPORTED（防御，理论不命中）。
        if (marginMode == TradingMarginMode.CROSS) {
            if (!riskSwitchCache.isEnabled(TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false)) {
                return reject("CROSS_MODE_NOT_ENABLED");
            }
        } else if (marginMode != TradingMarginMode.ISOLATED) {
            return reject("MARGIN_MODE_NOT_SUPPORTED");
        }

        // STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.2：从 Redis 读 SymbolSpec
        SymbolSpec spec = marketSymbolSpecRepository.findByPlatformSymbol(command.symbol()).orElse(null);
        if (spec == null) {
            return reject("SYMBOL_SPEC_NOT_FOUND");
        }
        // effective max leverage = min(spec.maxLeverage 产品上限, risk_config.max_leverage 风控兜底)
        BigDecimal specLeverage = spec.maxLeverage() == null
                ? properties.getMaxLeverage()
                : BigDecimal.valueOf(spec.maxLeverage());
        TradingRiskConfig riskConfig = tradingRiskConfigRepository.findBySymbol(command.symbol()).orElse(null);
        BigDecimal effectiveMaxLeverage = riskConfig != null && riskConfig.maxLeverage() != null
                ? specLeverage.min(BigDecimal.valueOf(riskConfig.maxLeverage()))
                : specLeverage;
        if (command.leverage().compareTo(effectiveMaxLeverage) > 0) {
            return reject("LEVERAGE_EXCEEDED");
        }
        // qty / notional 校验取 mapping spec
        if (spec.minQty() != null && command.quantity().compareTo(spec.minQty()) < 0) {
            return reject("QTY_BELOW_MIN");
        }
        if (spec.maxQty() != null && command.quantity().compareTo(spec.maxQty()) > 0) {
            return reject("QTY_ABOVE_MAX");
        }
        // 数量小数位不得超过 symbol qtyPrecision（防超精度脏数据入库）。
        if (spec.qtyPrecision() != null && scaleOf(command.quantity()) > spec.qtyPrecision()) {
            return reject("QTY_PRECISION_EXCEEDED");
        }

        // BBook 风控动作检查：全局暂停 → 品种级别最高优先级动作。
        // STAGE-14C2 Task 6：GLOBAL_PAUSE / FX_PAUSED 活跃时按 spec.category() 查 t_fx_pause_behavior 细分。
        String riskControlRejection = evaluateRiskControlRejection(command.symbol(), spec);
        if (riskControlRejection != null) {
            return reject(riskControlRejection);
        }

        // BBOOK-RISK-CONTROL-01：用户级敞口阈值（含盈利用户独立阈值）
        String userExposureRejection = evaluateUserExposureLimit(command, quote);
        if (userExposureRejection != null) {
            return reject(userExposureRejection);
        }

        // STAGE-12-GROUP-MARKUP: 应用组级加点到 fillPrice。
        // BUY → ask + askExtra（用户付出更多）；SELL → bid + bidExtra（卖价更低）。
        // bidExtra/askExtra 可正可负，由管理端配置，CHECK 兜底范围。
        MarketGroupMarkupItem markup = tradingGroupMarkupService
                .find(command.resolvedGroupCode(), command.symbol())
                .orElse(null);
        BigDecimal bidExtra = markup == null ? BigDecimal.ZERO : markup.bidExtra();
        BigDecimal askExtra = markup == null ? BigDecimal.ZERO : markup.askExtra();
        BigDecimal fillPrice = command.side() == TradingOrderSide.BUY
                ? quote.ask().add(askExtra)
                : quote.bid().add(bidExtra);
        BigDecimal notional = fillPrice.multiply(command.quantity());
        if (spec.minNotional() != null && notional.compareTo(spec.minNotional()) < 0) {
            return reject("NOTIONAL_BELOW_MIN");
        }
        // STAGE-14B Task 6：初始保证金接入 FX 换算。
        //   IM(QC) = Notional(QC)/lev（原币）→ IM(AC) = IM(QC) × fx(QC→AC)（账户币）。
        //   余额校验、资金占用（frozen/marginUsed）一律用账户币 inAccount；
        //   强平价仍用原币 inQuote（master §3.2 强平价保留原币计算）。
        String quoteCurrency = spec.quoteCurrency();
        // STAGE-14B Task 6 收口（C-1）：Task 5 过渡期 market 尚未重发布、Redis 存旧快照时，
        // SymbolSpec.quoteCurrency() 可能为 null/空。若直接传入 calculator 的 FX 路径，
        // 会在 quoteCurrency.equals(accountCurrency) 处对 null 调 equals → NPE → 非 USDT-quote
        // 品种过渡期开仓必现 500。语义上币种规格不完整应拒单而非崩溃，复用既有 SYMBOL_SPEC_NOT_FOUND
        // reason（不自创新错误码）。account.currency() 为 DB NOT NULL DEFAULT 'USDT'，此处只防守 quoteCurrency。
        if (quoteCurrency == null || quoteCurrency.isBlank()) {
            return reject("SYMBOL_SPEC_NOT_FOUND");
        }
        String accountCurrency = account.currency();
        MarginResult margin = marginCalculator.calculateInitialMargin(
                fillPrice, command.quantity(), command.leverage(),
                quoteCurrency, accountCurrency, fxRateService);
        if (margin.inAccount() == null) {
            // FX 路径缺失（quote≠account 且 market 行情未到）→ 拒单。
            // reason 对应 master §7.3 错误码 30073 FX_RATE_UNAVAILABLE。
            return reject("FX_RATE_UNAVAILABLE");
        }
        // fee 用 mapping.taker_fee_rate（spec）；缺失时回退 properties.defaultFeeRate（不应发生，spec 必校验）
        BigDecimal feeRate = spec.takerFeeRate() != null ? spec.takerFeeRate() : properties.getDefaultFeeRate();
        // STAGE-14B Task 8：手续费接入 FX 换算（收口 Task 6 标注的混币 bug）。
        //   Fee(QC) = Notional(QC) × feeRate（原币）→ Fee(AC) = Fee(QC) × fx(QC→AC)（账户币）。
        //   复用 margin 同一 quoteCurrency/accountCurrency 上下文（同 symbol、同账户币）。
        MarginResult fee = marginCalculator.calculateFee(
                fillPrice, command.quantity(), feeRate,
                quoteCurrency, accountCurrency, fxRateService);
        // FX 不可用防御：与 margin 同币种上下文，FX 不可用两者必同时为 null，前面 margin.inAccount()==null
        // 已先触发拒单；此处仍显式防守 fee.inAccount()==null，保证拒单路径覆盖 fee（reason 同 30073）。
        if (fee.inAccount() == null) {
            return reject("FX_RATE_UNAVAILABLE");
        }
        // Task 8 收口：margin 与 fee 现统一为账户币 inAccount 口径，totalRequired 不再混币。
        BigDecimal totalRequired = margin.inAccount().add(fee.inAccount());

        if (account.available().compareTo(totalRequired) < 0) {
            return reject("INSUFFICIENT_AVAILABLE_BALANCE");
        }

        String positionLimitRejectionReason = evaluatePositionLimitRejectionReason(command);
        if (positionLimitRejectionReason != null) {
            return reject(positionLimitRejectionReason);
        }

        // STAGE-14C1 Task 6：接入 LeverageTierResolver，按账户币 notional 解析杠杆/MM 档位。
        //   notional(AC) = IM(AC) × lev = margin.inAccount() × leverage（与 master §3.2 一致，
        //   等价于 Notional(QC)×fx；这里直接用账户币 IM×lev 复用已算好的 margin.inAccount，避免重复换算）。
        //   走到这里 margin.inAccount() 必非 null（前面 FX 不可用已先拒单），故 notional(AC) 始终可算。
        BigDecimal notionalInAccount = margin.inAccount().multiply(command.leverage());
        Optional<LeverageTier> tier = leverageTierResolver
                .resolve(command.symbol(), notionalInAccount, command.resolvedGroupCode());
        if (tier.isEmpty()) {
            // 无 tier 配置 / 落不进任何档 → master §7.3 错误码 30072 TIER_CONFIG_NOT_FOUND。
            return reject("TIER_CONFIG_NOT_FOUND");
        }
        LeverageTier resolvedTier = tier.get();
        // tier 上限是按 notional 档位的更细约束；既有 min(SymbolSpec, riskConfig) 全局上限保留（前置已校验），
        // 这里再叠加一道 tier 上限校验。lev 超 tier 上限 → master §7.3 错误码 30070 LEVERAGE_EXCEEDS_TIER。
        if (command.leverage().compareTo(BigDecimal.valueOf(resolvedTier.maxLeverage())) > 0) {
            return reject("LEVERAGE_EXCEEDS_TIER");
        }

        // 强平价改用 tier mmRate（替换历史硬码 properties.getMaintenanceMarginRate()）。
        // mmRate 同时冻结到 decision → t_position.mm_rate_at_open（后续 tier 调整不影响存量仓位强平价）。
        BigDecimal mmRate = resolvedTier.mmRate();
        // STAGE-14D2 Task 2：强平价按模式分流（master §3.2）。
        //   ISOLATED：保留原币单仓强平价计算（liqPrice 命中或单仓 MarginLevel≤30% 双触发）。
        //   CROSS：不存单仓 liqPrice（写 NULL）→ 改账户级 MarginLevel 触发（Task 4 CrossLiquidationOrchestrator）。
        //   注意：tier/IM/fee 校验与 IM 冻结（margin.inAccount→frozen）对 CROSS/ISOLATED 一致，仅此处分流。
        BigDecimal liquidationPrice = marginMode == TradingMarginMode.CROSS
                ? null
                : liquidationPriceCalculator.calculate(
                        command.side(),
                        fillPrice,
                        command.quantity(),
                        margin.inQuote(),
                        mmRate
                );
        // B 切片守卫（2026-06-03）：强平价距离 ≤ 点差×N → 拒单 30071（master §5.1 修正记录的防呆层）。
        //   背景：tier 配置若违反 lev×mm≤0.5 设计准则（DB CHECK 只保证 ≤1.0），满杠杆开仓缓冲可
        //   小于点差 → 按对手价成交后标记价（对手价）立即低于强平价瞬时强平（demo 实证 AUDCAD
        //   300x×mm0.33% 开仓 199ms 被强平）。V38 已修正 seed，本守卫防未来 admin 自配 tier 复踩。
        //   开仓即刻回撤约一个点差（BUY 按 bid 标记 / SELL 按 ask），要求距离 > 点差×2 放行；
        //   CROSS 无单仓 liqPrice，由账户级 MarginLevel 体系兜底，不适用本守卫。
        if (liquidationPrice != null) {
            BigDecimal spread = quote.ask().subtract(quote.bid()).abs();
            BigDecimal liqDistance = fillPrice.subtract(liquidationPrice).abs();
            if (liqDistance.compareTo(spread.multiply(LIQ_DISTANCE_MIN_SPREAD_MULTIPLE)) <= 0) {
                log.warn("trading.risk.liq-distance.too-close symbol={} lev={} fill={} liq={} spread={} distance={}",
                        command.symbol(), command.leverage(), fillPrice, liquidationPrice, spread, liqDistance);
                return reject("LIQUIDATION_DISTANCE_TOO_CLOSE");
            }
        }
        // TradingRiskDecision.margin/fee 写入 t_account.frozen/marginUsed/balance（chargeFee）与
        // t_position.margin、t_order.fee、t_trade.fee（账户币口径）→ 均传 inAccount，与下游 chargeFee
        // 扣账户币 balance 一致。
        // STAGE-14B Task 9a：额外携带 fxRate / feeFxRate / quoteCurrency / 原币值（marginInQuote/feeInQuote），
        // 供应用层把开仓 margin/fee 落账三列写成真值（amount=inAccount、original=inQuote、
        // currency=quoteCurrency、fx=各自查询时刻 fxRate），并写 t_position.entry_fx_rate。
        // 原币值直接取 calculator 的 inQuote，不用 inAccount/fxRate 反推（避免精度损失）。
        // STAGE-14B Task 9a 收口：margin 与 fee 各自查询一次 FX 快照（calculateInitialMargin /
        // calculateFee 各调一次 FxRateService），两次之间快照可能被刷新而取到不同值。因此 margin 账
        // 用 margin.fxRate()（fxRate 字段，同时供 entry_fx_rate），fee 账用 fee.fxRate()（feeFxRate 字段），
        // 保证各自账本三列 original×fx==amount 自洽。
        return new TradingRiskDecision(
                true, null, fillPrice,
                margin.inAccount(), fee.inAccount(), feeRate, liquidationPrice,
                margin.fxRate(), fee.fxRate(), quoteCurrency, margin.inQuote(), fee.inQuote(),
                // STAGE-14C1 Task 6：冻结 tier mmRate / tierNo → 开仓写 t_position.mm_rate_at_open / tier_no_at_open。
                mmRate, resolvedTier.tierNo());
    }

    /**
     * BBOOK-RISK-CONTROL-01：用户级 USD 敞口阈值评估。
     *
     * <p>累计该用户全部 OPEN 持仓的 |quantity × markPrice|（按各 symbol 当前 quote 估值）
     * 加上本次新下单的 notional，超过用户阈值则拒。
     * 盈利用户走更严格的 profitableNetExposureThresholdUsd。
     */
    private String evaluateUserExposureLimit(PlaceMarketOrderCommand command, TradingQuoteSnapshot currentQuote) {
        TradingUserRiskThreshold threshold = tradingUserRiskThresholdRepository
                .findByUserId(command.userId()).orElse(null);
        if (threshold == null) {
            return null;
        }
        BigDecimal effectiveThreshold = threshold.profitableUser()
                ? threshold.profitableNetExposureThresholdUsd()
                : threshold.netExposureThresholdUsd();
        if (effectiveThreshold == null || effectiveThreshold.signum() <= 0) {
            return null;
        }
        List<TradingPosition> openPositions = tradingPositionRepository.findOpenByUserId(command.userId());
        // 2026-05-26 性能加固（Sprint 2 S3 / 性能分析报告 §6 P0）：
        // 原实现：循环对每个非本 symbol 持仓单查 Redis findBySymbol。
        // 用户 100 持仓覆盖 50 symbol → 49 次 Redis HGETALL = 49 RTT，下单 10-50ms 都在这。
        // 改用 findBySymbols(Collection) pipeline 1 RTT 批量取。
        Set<String> otherSymbols = new LinkedHashSet<>();
        for (TradingPosition p : openPositions) {
            if (!p.symbol().equals(command.symbol())) {
                otherSymbols.add(p.symbol());
            }
        }
        Map<String, TradingQuoteSnapshot> otherSnapshots = otherSymbols.isEmpty()
                ? Map.of()
                : tradingQuoteSnapshotRepository.findBySymbols(otherSymbols);
        BigDecimal currentExposureUsd = BigDecimal.ZERO;
        for (TradingPosition p : openPositions) {
            BigDecimal markPrice;
            if (p.symbol().equals(command.symbol())) {
                markPrice = TradingPricingSupport.resolvePositionMarkPrice(currentQuote, p.side());
            } else {
                TradingQuoteSnapshot q = otherSnapshots.get(p.symbol());
                markPrice = q == null ? null : TradingPricingSupport.resolvePositionMarkPrice(q, p.side());
            }
            if (markPrice == null) {
                continue;
            }
            currentExposureUsd = currentExposureUsd.add(p.quantity().abs().multiply(markPrice));
        }
        BigDecimal incomingFillPrice = command.side() == TradingOrderSide.BUY ? currentQuote.ask() : currentQuote.bid();
        BigDecimal incomingNotionalUsd = command.quantity().multiply(incomingFillPrice);
        BigDecimal projected = currentExposureUsd.add(incomingNotionalUsd);
        if (projected.compareTo(effectiveThreshold) > 0) {
            return "BBOOK_RISK_USER_EXPOSURE_LIMIT";
        }
        return null;
    }

    private String evaluateRiskControlRejection(String symbol, SymbolSpec spec) {
        if (tradingRiskControlActionRepository.hasActiveGlobalPause()) {
            // STAGE-14C2 Task 6：FX_PAUSED 升级为 GLOBAL_PAUSE 后，开仓不再一刀切全拒，
            // 而是按品种类目（master §6.5）查 t_fx_pause_behavior 决定是否允许开仓。
            String pauseRejection = evaluatePauseOpenRejection(symbol, spec);
            if (pauseRejection != null) {
                return pauseRejection;
            }
            // pauseRejection==null 表示该类目 allow_open=true → 不因 pause 拒，
            // 继续走品种级别风控动作（REJECT_OPEN / REDUCE_ONLY / SUSPEND_SYMBOL）。
        }
        TradingRiskControlActionType action =
                tradingRiskControlActionRepository.findMostSevereActiveBySymbol(symbol).orElse(null);
        if (action == null) {
            return null;
        }
        return switch (action) {
            case REJECT_OPEN     -> "BBOOK_RISK_OPEN_REJECTED";
            case REDUCE_ONLY     -> "BBOOK_RISK_REDUCE_ONLY";
            case SUSPEND_SYMBOL  -> "SYMBOL_TRADING_SUSPENDED";
            case GLOBAL_PAUSE    -> "BBOOK_RISK_GLOBAL_PAUSE";
        };
    }

    /**
     * STAGE-14C2 Task 6：GLOBAL_PAUSE / FX_PAUSED 活跃时，按品种类目（master §6.5）判定是否允许开仓。
     *
     * <ul>
     *   <li>category 非 null 且 t_fx_pause_behavior 命中：
     *     allow_open=false → reject {@code GLOBAL_PAUSE_ACTIVE}（30087）；allow_open=true → 返回 null（放行）。</li>
     *   <li><b>保守降级</b>：category==null（过渡期旧快照）或 behavior 缺失（findByCategory empty）→
     *     回退原一刀切全拒（{@code BBOOK_RISK_GLOBAL_PAUSE}）+ warn。FX_PAUSED 是风控暂停态，缺信息时
     *     从严不从宽，宁可在 pause 期间多拒一笔，也不放过高风险开仓（与强平降级方向相反，强平缺信息倾向继续）。</li>
     * </ul>
     *
     * <p>平仓不经过本路径：master §6.5 allow_close 始终=1，用户手动平仓不加 pause 限制。
     *
     * @return 拒单 reason；null 表示该类目允许开仓（pause 不拒，继续后续风控）
     */
    private String evaluatePauseOpenRejection(String symbol, SymbolSpec spec) {
        Integer category = spec == null ? null : spec.category();
        if (category == null) {
            log.warn("trading.fxpause.open.degrade.category-null symbol={} action=blanket-reject reason=BBOOK_RISK_GLOBAL_PAUSE",
                    symbol);
            return "BBOOK_RISK_GLOBAL_PAUSE";
        }
        Optional<FxPauseBehavior> behavior = fxPauseBehaviorRepository.findByCategory(category);
        if (behavior.isEmpty()) {
            log.warn("trading.fxpause.open.degrade.behavior-missing symbol={} category={} action=blanket-reject reason=BBOOK_RISK_GLOBAL_PAUSE",
                    symbol, category);
            return "BBOOK_RISK_GLOBAL_PAUSE";
        }
        if (!behavior.get().allowOpen()) {
            log.warn("trading.fxpause.open.rejected symbol={} category={} allowOpen=false reason=GLOBAL_PAUSE_ACTIVE",
                    symbol, category);
            return "GLOBAL_PAUSE_ACTIVE";
        }
        // allow_open=true：该类目（如 crypto）暂停期间允许开仓，不因 pause 拒。
        return null;
    }

    private String evaluatePositionLimitRejectionReason(PlaceMarketOrderCommand command) {
        TradingRiskConfig riskConfig = tradingRiskConfigRepository.findBySymbol(command.symbol()).orElse(null);
        if (riskConfig == null) {
            return null;
        }
        if (isLimitReached(
                tradingPositionRepository.countOpenByUserIdAndSymbol(command.userId(), command.symbol()),
                riskConfig.maxPositionPerUser()
        )) {
            return "POSITION_LIMIT_REACHED";
        }
        if (isLimitReached(
                tradingPositionRepository.countOpenBySymbol(command.symbol()),
                riskConfig.maxPositionTotal()
        )) {
            return "PLATFORM_POSITION_LIMIT_REACHED";
        }
        return null;
    }

    private boolean isLimitReached(long openPositionCount, BigDecimal limit) {
        return limit != null
                && limit.signum() > 0
                && BigDecimal.valueOf(openPositionCount).compareTo(limit) >= 0;
    }

    /** BigDecimal 有效小数位（去尾零后；整数/负 scale 归 0）。 */
    private static int scaleOf(java.math.BigDecimal v) {
        return Math.max(0, v.stripTrailingZeros().scale());
    }

    private TradingRiskDecision reject(String reason) {
        // STAGE-14B Task 9a：reject 不进落账路径，货币字段（fxRate/feeFxRate/quoteCurrency/原币值）一律 null。
        // STAGE-14C1 Task 6：reject 不进开仓 INSERT 路径，mmRateAtOpen/tierNoAtOpen 同为 null。
        return new TradingRiskDecision(false, reason, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
