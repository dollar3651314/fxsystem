package com.falconx.trading.calculator;

import java.math.BigDecimal;

/**
 * STAGE-14B Task 6：初始保证金计算结果（多币种）。
 *
 * <p>按 master §3.2 公式拆分两种币别口径，外加结算用的 FX rate：
 *
 * <ul>
 *   <li>{@code inQuote}：原币（quote currency）保证金 {@code IM(QC) = Notional(QC) / lev}，
 *       供强平价等保留原币计算的算法使用。</li>
 *   <li>{@code inAccount}：账户币（account currency）保证金 {@code IM(AC) = IM(QC) × fx(QC→AC)}，
 *       写入 {@code t_account.frozen} / {@code marginUsed}，是余额校验与资金占用口径。</li>
 *   <li>{@code fxRate}：当次换算使用的 {@code fx(quoteCurrency → accountCurrency)}；
 *       同币种为 {@link BigDecimal#ONE}。</li>
 * </ul>
 *
 * <p><b>FX 不可用约定</b>：当 quote≠account 且 FX 路径缺失时，{@code inAccount} 与 {@code fxRate}
 * 均为 {@code null}（{@code inQuote} 仍照常给出），由调用方检测 {@code inAccount == null} 拒单
 * （对应 master §7.3 错误码 30073 FX_RATE_UNAVAILABLE），计算器本身不抛异常。
 *
 * @param inQuote   原币保证金（quote currency，8 位 DOWN），非空
 * @param inAccount 账户币保证金（account currency，8 位 HALF_UP）；FX 不可用时为 {@code null}
 * @param fxRate    fx(quoteCurrency → accountCurrency)；同币种为 {@code ONE}；FX 不可用时为 {@code null}
 */
public record MarginResult(BigDecimal inQuote, BigDecimal inAccount, BigDecimal fxRate) {
}
