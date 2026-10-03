package com.falconx.trading.service;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.service.model.AccountMarginState;
import java.math.BigDecimal;
import java.util.List;

/**
 * 账户净值 / 保证金率计算器。
 *
 * <p>STAGE-14C1 Task 7 引入。对齐 master §3.2「账户净值 Equity」「维持保证金 MM」「保证金率 MarginLevel」，
 * C1 仅实现 <b>ISOLATED</b> 口径（CROSS 账户级逐仓强平排序延 STAGE-14D，本接口预留账户级方法但不做 CROSS 分支）。
 *
 * <p>两条口径：
 * <ul>
 *   <li>{@link #computePositionMarginLevel}：单仓口径，StopOut 主判定（每仓独立触发）。</li>
 *   <li>{@link #computeAccountMarginLevel}：ISOLATED 跨仓汇总，供账户级展示 / CROSS 预留。</li>
 * </ul>
 *
 * <p>uPnL(AC) 一律复用 {@code TradingPricingSupport.calculatePositionPnlInAccount}（STAGE-14B Task 7），
 * 该方法已正确处理 STAGE-12 markup + FX 换算；FX 不可用时 {@code inAccount=null} → 本计算器降级（marginLevel=null）。
 *
 * <p>MM 一律用持仓 <b>开仓冻结</b>的 {@code position.mmRateAtOpen()}（master「mmRate 冻结」决策），
 * 后续 tier 调整不影响存量仓位强平判定。
 */
public interface AccountEquityCalculator {

    /**
     * 单仓 MarginLevel（StopOut 用，C1 ISOLATED 主路径）。
     *
     * <p>口径（master §3.2 ISOLATED 单仓判定）：
     * <pre>
     *   uPnL_i(AC) = calculatePositionPnlInAccount(position, effectiveMarkPrice, quoteCurrency, AC, fx).inAccount()
     *   notional_i(AC) = qty × entryPrice × entryFxRate          (账户币 notional，entryFxRate 为开仓冻结 fx(QC→AC))
     *   MM_i(AC)   = notional_i(AC) × position.mmRateAtOpen()
     *   Equity_i   = position.margin()(AC) + uPnL_i(AC)
     *   MarginLevel_i = Equity_i / MM_i × 100
     * </pre>
     *
     * <p>降级：uPnL inAccount 为 null（FX 不可用 / markPrice 缺失）→ equity/marginLevel 置 null（caller 不强平）；
     * MM_i == 0 → marginLevel 置 null（避免除零）。
     *
     * @param position           持仓（提供 qty/entryPrice/entryFxRate/mmRateAtOpen/margin）
     * @param account            账户（提供 currency 作为 AC）
     * @param effectiveMarkPrice 有效标记价（基准 bid/ask，内部由 PricingSupport 叠加冻结 markup）
     * @param quoteCurrency      计价币代码（QC）；TradingPosition 无 quoteCurrency 字段，由 caller 从 SymbolSpec 传入；可为 null
     * @return 单仓 {@link AccountMarginState}
     */
    AccountMarginState computePositionMarginLevel(TradingPosition position,
                                                  TradingAccount account,
                                                  BigDecimal effectiveMarkPrice);

    /**
     * 单仓 MarginLevel，显式传入 quoteCurrency（TradingPosition 不持有 QC）。
     *
     * @see #computePositionMarginLevel(TradingPosition, TradingAccount, BigDecimal)
     */
    AccountMarginState computePositionMarginLevel(TradingPosition position,
                                                  TradingAccount account,
                                                  BigDecimal effectiveMarkPrice,
                                                  String quoteCurrency);

    /**
     * 账户级 MarginLevel（ISOLATED 跨仓汇总，供展示 / CROSS 预留）。
     *
     * <p>口径（master §3.2 / §3.3 账户级）：
     * <pre>
     *   Equity   = balance + frozen + Σ uPnL_i(AC)
     *   totalMM  = Σ MM_i(AC)
     *   MarginLevel = Equity / totalMM × 100
     * </pre>
     *
     * <p>边界：空仓位（totalMM=0）→ marginLevel=null（语义「无持仓」），totalMM 返回 0；
     * 任一仓 uPnL inAccount 为 null（FX 不可用）→ equity/marginLevel 置 null（caller 降级）。
     *
     * <p>CROSS 逐仓强平排序延 STAGE-14D，本方法只做汇总计算，不做强平决策。
     *
     * @param account   账户（balance/frozen/currency）
     * @param positions 该账户全部 OPEN 持仓及其标记价 / 计价币输入
     * @return 账户级 {@link AccountMarginState}
     */
    AccountMarginState computeAccountMarginLevel(TradingAccount account,
                                                 List<PositionMarkInput> positions);

    /**
     * 账户级汇总的单仓输入：持仓 + 当前有效标记价 + 计价币代码（QC，position 不持有）。
     *
     * @param position           持仓
     * @param effectiveMarkPrice 有效标记价（基准 bid/ask）
     * @param quoteCurrency      计价币代码（QC），可为 null（FX 不可用降级）
     */
    record PositionMarkInput(TradingPosition position, BigDecimal effectiveMarkPrice, String quoteCurrency) {
    }
}
