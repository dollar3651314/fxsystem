package com.falconx.trading.support;

import java.math.BigDecimal;

/**
 * STAGE-14B Task 7：货币感知盈亏计算结果（持仓浮盈亏 / 已实现盈亏）。
 *
 * <p>对齐 master §3.2「已实现盈亏 Realized PnL」与「未实现盈亏 Unrealized PnL」公式，
 * 在原币 PnL 之上叠加账户币换算口径，并保留当次 fxRate 与原币代码用于落账留痕
 * （{@code t_ledger.original_amount / original_currency / fx_rate_at_settlement}，由 Task 9 接通写账）：
 *
 * <ul>
 *   <li>{@code inQuote}：原币（quote currency）PnL（= {@code RealizedPnL(QC)} / {@code UnrealizedPnL(QC)}），
 *       即原 {@link TradingPricingSupport#calculatePositionPnl} 的返回值（含 STAGE-12 markup 处理）。</li>
 *   <li>{@code inAccount}：账户币（account currency）PnL {@code = inQuote × fxRate}（8 位 HALF_UP）；
 *       是写入 balance / equity 的口径。FX 不可用或 quoteCurrency 缺失时为 {@code null}。</li>
 *   <li>{@code fxRate}：当次换算使用的 {@code fx(quoteCurrency → accountCurrency)}；
 *       同币种为 {@link BigDecimal#ONE}；FX 不可用或 quoteCurrency 缺失时为 {@code null}。</li>
 *   <li>{@code quoteCurrency}：原币代码（落账 original_currency 留痕）；caller 传入可能为 {@code null}
 *       （SymbolSpec 过渡期 quoteCurrency 可能未回填），此时如实回填 {@code null}。</li>
 * </ul>
 *
 * <p><b>符号约定</b>：PnL 可正可负（盈利 / 亏损），{@code inAccount} 的 8 位 HALF_UP 舍入对负数
 * 向绝对值更大方向取整，符合金额留痕口径。
 *
 * <p><b>FX 不可用约定</b>：当 quote≠account 且 FX 路径缺失（或 quoteCurrency 为空）时，
 * {@code inAccount} 与 {@code fxRate} 均为 {@code null}（{@code inQuote} 仍照常给出），
 * 计算逻辑本身不抛异常，由调用方（Task 9 落账编排）据此决定降级或拒绝。
 *
 * @param inQuote       原币 PnL（quote currency），可正可负；position/markPrice 缺失时为 {@code null}
 * @param inAccount     账户币 PnL（account currency，8 位 HALF_UP）；FX 不可用或 quoteCurrency 缺失时为 {@code null}
 * @param fxRate        fx(quoteCurrency → accountCurrency)；同币种为 {@code ONE}；FX 不可用或 quoteCurrency 缺失时为 {@code null}
 * @param quoteCurrency 原币代码（留痕用）；caller 未提供时为 {@code null}
 */
public record PnlResult(BigDecimal inQuote, BigDecimal inAccount, BigDecimal fxRate, String quoteCurrency) {
}
