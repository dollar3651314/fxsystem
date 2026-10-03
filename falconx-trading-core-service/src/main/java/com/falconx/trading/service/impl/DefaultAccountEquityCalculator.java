package com.falconx.trading.service.impl;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.service.AccountEquityCalculator;
import com.falconx.trading.service.FxRateService;
import com.falconx.trading.service.model.AccountMarginState;
import com.falconx.trading.support.PnlResult;
import com.falconx.trading.support.TradingPricingSupport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link AccountEquityCalculator} 默认实现（C1 仅 ISOLATED）。
 *
 * <p>对齐 master §3.2/§3.3。两条不变量：
 * <ul>
 *   <li><b>uPnL(AC) 复用 STAGE-14B</b> {@link TradingPricingSupport#calculatePositionPnlInAccount}：
 *       markup + FX 换算逻辑唯一来源，不重复实现（DRY）。其 {@code inAccount==null}
 *       （FX 不可用 / markPrice 缺失 / QC 缺失）即触发本计算器降级。</li>
 *   <li><b>MM 用实时 FX（STAGE-14D2 Task 3）</b>：{@code MM_i(AC) = qty × entryPrice × mmRateAtOpen × fx(QC→AC)}，
 *       其中 <b>fx 用实时 {@link FxRateService#queryRate}</b>（master §3.2 D2「全部实时」），<b>mmRate 仍冻结
 *       mmRateAtOpen</b>（tier 调整不影响存量仓）。FX 不可用（queryRate empty）→ 降级用 {@code position.entryFxRate()}
 *       （最后已知冻结值，master §3.5.5「用最后 rate，不停 MM」）+ warn 日志；同币种 fx=1（不查询）。</li>
 * </ul>
 *
 * <p>MarginLevel 以百分比给出（已 ×100），2 位 {@link RoundingMode#HALF_UP}；
 * 金额（equity/MM）保持各自参与运算的精度。
 *
 * <p><b>CROSS 延 STAGE-14D</b>：账户级逐仓强平排序不在本类实现；本类账户级方法只做汇总计算。
 */
@Service
public class DefaultAccountEquityCalculator implements AccountEquityCalculator {

    private static final Logger log = LoggerFactory.getLogger(DefaultAccountEquityCalculator.class);

    /** MarginLevel 百分比展示精度。 */
    private static final int MARGIN_LEVEL_SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final FxRateService fxRateService;

    public DefaultAccountEquityCalculator(FxRateService fxRateService) {
        this.fxRateService = fxRateService;
    }

    @Override
    public AccountMarginState computePositionMarginLevel(TradingPosition position,
                                                         TradingAccount account,
                                                         BigDecimal effectiveMarkPrice) {
        // TradingPosition 不持有 quoteCurrency；无 caller 传入时无法换算 → 走 null 分支降级。
        return computePositionMarginLevel(position, account, effectiveMarkPrice, null);
    }

    @Override
    public AccountMarginState computePositionMarginLevel(TradingPosition position,
                                                         TradingAccount account,
                                                         BigDecimal effectiveMarkPrice,
                                                         String quoteCurrency) {
        BigDecimal mm = maintenanceMargin(position, quoteCurrency, account.currency());
        BigDecimal uPnlAccount = unrealizedPnlInAccount(position, effectiveMarkPrice, quoteCurrency, account.currency());

        if (uPnlAccount == null) {
            // FX 不可用 / markPrice 缺失 → 净值不可知，equity/marginLevel 降级 null（caller 不强平）；
            // MM 用冻结口径仍可给出。
            return new AccountMarginState(null, mm, null);
        }

        // 单仓 Equity_i = margin_i(AC) + uPnL_i(AC)
        BigDecimal equity = nonNull(position.margin()).add(uPnlAccount);
        BigDecimal marginLevel = marginLevel(equity, mm);
        return new AccountMarginState(equity, mm, marginLevel);
    }

    @Override
    public AccountMarginState computeAccountMarginLevel(TradingAccount account,
                                                        List<PositionMarkInput> positions) {
        BigDecimal totalMm = BigDecimal.ZERO;
        BigDecimal sumUpnl = BigDecimal.ZERO;
        boolean fxDegraded = false;

        for (PositionMarkInput input : positions) {
            TradingPosition position = input.position();
            totalMm = totalMm.add(maintenanceMargin(position, input.quoteCurrency(), account.currency()));

            BigDecimal uPnlAccount = unrealizedPnlInAccount(
                    position, input.effectiveMarkPrice(), input.quoteCurrency(), account.currency());
            if (uPnlAccount == null) {
                // 任一仓不可换算即整体净值不可知；继续累加 MM（冻结口径）以便展示。
                fxDegraded = true;
            } else if (!fxDegraded) {
                sumUpnl = sumUpnl.add(uPnlAccount);
            }
        }

        // Equity = balance + frozen + Σ uPnL_i(AC)；任一仓 FX 不可用 → equity/marginLevel 降级 null。
        BigDecimal equity = fxDegraded
                ? null
                : nonNull(account.balance()).add(nonNull(account.frozen())).add(sumUpnl);

        // 空仓位（totalMM=0）→ marginLevel=null（无持仓语义，避免除零）；FX 降级时同样 null。
        BigDecimal marginLevel = (equity == null) ? null : marginLevel(equity, totalMm);
        return new AccountMarginState(equity, totalMm, marginLevel);
    }

    /**
     * MM_i(AC) = qty × entryPrice × mmRateAtOpen × fx(QC→AC)（STAGE-14D2 Task 3，master §3.2 D2「全部实时」）。
     *
     * <ul>
     *   <li><b>fx 实时</b>：{@code fxRateService.queryRate(quoteCurrency, accountCurrency)}（替换开仓冻结 entryFxRate）。</li>
     *   <li><b>mmRate 冻结</b>：仍用 {@code position.mmRateAtOpen()}（tier 调整不影响存量仓强平判定）。</li>
     *   <li><b>同币种</b>（QC==AC）：fx=1，不查询。</li>
     *   <li><b>FX 不可用降级</b>（queryRate empty）：用 {@code position.entryFxRate()}（最后已知冻结值，
     *       master §3.5.5「用最后 rate，不停 MM」）+ warn；entryFxRate 亦缺失则用 1（兜底，避免 NPE）。</li>
     * </ul>
     *
     * <p>缺失 qty/entryPrice/mmRateAtOpen 任一返回 0（不参与 MM 累加，避免 NPE）。
     *
     * @param quoteCurrency   计价币 QC（caller 从 SymbolSpec 传入，可为 null）
     * @param accountCurrency 账户币 AC
     */
    private BigDecimal maintenanceMargin(TradingPosition position, String quoteCurrency, String accountCurrency) {
        if (position == null
                || position.quantity() == null
                || position.entryPrice() == null
                || position.mmRateAtOpen() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal notionalQuote = position.quantity().multiply(position.entryPrice());
        BigDecimal fx = resolveMmFxRate(position, quoteCurrency, accountCurrency);
        return notionalQuote.multiply(position.mmRateAtOpen()).multiply(fx);
    }

    /**
     * 解析 MM 用 fx(QC→AC)：实时 queryRate 优先；同币种=1；FX 不可用降级 entryFxRate（最后已知）+ warn；
     * QC 缺失且非同币种亦降级 entryFxRate（无法实时换算）。
     */
    private BigDecimal resolveMmFxRate(TradingPosition position, String quoteCurrency, String accountCurrency) {
        // 同币种短路：fx=1，不查询。QC 为 null/blank 时无法判定同币种，落到降级分支。
        if (quoteCurrency != null && !quoteCurrency.isBlank() && quoteCurrency.equals(accountCurrency)) {
            return BigDecimal.ONE;
        }
        if (quoteCurrency != null && !quoteCurrency.isBlank() && accountCurrency != null) {
            Optional<BigDecimal> realtime = fxRateService.queryRate(quoteCurrency, accountCurrency);
            if (realtime.isPresent()) {
                return realtime.get();
            }
        }
        // FX 不可用 / QC 缺失：降级用开仓冻结 entryFxRate（master §3.5.5 用最后 rate，不停 MM）+ warn。
        BigDecimal entryFx = position.entryFxRate();
        log.warn("MM 实时 fx 不可用，降级用 entryFxRate：positionId={} QC={} AC={} entryFxRate={}",
                position.positionId(), quoteCurrency, accountCurrency, entryFx);
        return entryFx == null ? BigDecimal.ONE : entryFx;
    }

    /** uPnL_i(AC)，复用 STAGE-14B Task 7 货币感知 PnL；inAccount==null 即降级信号。 */
    private BigDecimal unrealizedPnlInAccount(TradingPosition position,
                                              BigDecimal effectiveMarkPrice,
                                              String quoteCurrency,
                                              String accountCurrency) {
        PnlResult pnl = TradingPricingSupport.calculatePositionPnlInAccount(
                position, effectiveMarkPrice, quoteCurrency, accountCurrency, fxRateService);
        return pnl.inAccount();
    }

    /** MarginLevel = equity / MM × 100；MM<=0 → null（除零 / 无持仓）。 */
    private BigDecimal marginLevel(BigDecimal equity, BigDecimal mm) {
        if (mm == null || mm.signum() <= 0) {
            return null;
        }
        return equity.multiply(HUNDRED).divide(mm, MARGIN_LEVEL_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
