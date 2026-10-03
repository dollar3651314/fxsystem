package com.falconx.trading.service.model;

import java.math.BigDecimal;

/**
 * 风控决策结果。
 *
 * <p>该对象用于把风控校验输出从服务层传递到应用层，
 * 避免应用层再次重复计算成交价、保证金、手续费和强平价。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.2：新增 {@code openFeeRate} 字段，
 * 表示开仓时的费率快照（来自 SymbolSpec），由应用层落 t_order.open_fee_rate 与
 * t_position.open_fee_rate，供后续平仓 / 强平 / Swap 结算复用。
 *
 * <p>STAGE-14B Task 9a：新增 {@code fxRate} / {@code quoteCurrency} / {@code marginInQuote} /
 * {@code feeInQuote} 四个字段，承载开仓 margin/fee 落账三列真值所需信息：
 *
 * <ul>
 *   <li>{@code margin} / {@code fee} 仍为账户币 inAccount（驱动 balance/frozen/marginUsed
 *       变更与 t_order/t_position/t_trade 写值，口径不变）。</li>
 *   <li>{@code marginInQuote} / {@code feeInQuote} 为对应原币值 inQuote，直接来自 calculator
 *       （避免用 inAccount/fxRate 反推造成精度损失），落 t_ledger.original_amount。</li>
 *   <li>{@code quoteCurrency} 为计价币（来自 SymbolSpec），落 t_ledger.original_currency 与
 *       t_position.entry_fx_rate 的语义上下文。</li>
 *   <li>{@code fxRate} 为开仓时 margin 查询时刻的 fx(quoteCurrency→accountCurrency)，落 margin 两笔
 *       ledger（RESERVED/CONFIRMED）的 fx_rate_at_settlement 与 t_position.entry_fx_rate；同币种为
 *       {@link BigDecimal#ONE}。</li>
 *   <li>{@code feeFxRate} 为开仓时 fee 查询时刻的 fx(quoteCurrency→accountCurrency)，落 fee 账
 *       （ORDER_FEE_CHARGED）的 fx_rate_at_settlement；同币种为 {@link BigDecimal#ONE}。</li>
 * </ul>
 *
 * <p>STAGE-14B Task 9a 收口：margin 与 fee 在 {@code evaluateMarketOrder} 中各自独立查询一次 FX
 * 快照，两次查询之间快照可能被增量刷新而取到不同值。因此 margin 账与 fee 账各自携带<b>自身查询时刻</b>的
 * fxRate（{@code fxRate} 对应 margin，{@code feeFxRate} 对应 fee），保证各自账本三列
 * {@code original_amount × fx_rate_at_settlement = amount} 数学自洽，避免审计断裂。
 *
 * <p>reject 路径上五个字段（fxRate / feeFxRate / quoteCurrency / marginInQuote / feeInQuote）
 * 均为 {@code null}（不会进入落账路径）。
 *
 * <p>STAGE-14C1 Task 6：新增 {@code mmRateAtOpen} / {@code tierNoAtOpen} 两个字段，承载开仓时
 * 由 {@code LeverageTierResolver} 按账户币 notional 解析出的命中档位维持保证金率与档位号：
 *
 * <ul>
 *   <li>{@code mmRateAtOpen} 为该档 mmRate，替换历史硬码 {@code properties.getMaintenanceMarginRate()}
 *       作为强平价计算入参，并冻结到 {@code t_position.mm_rate_at_open}（后续 tier 调整不影响存量仓位强平价）。</li>
 *   <li>{@code tierNoAtOpen} 为命中档位号，冻结到 {@code t_position.tier_no_at_open}（审计用）。</li>
 *   <li>reject 路径两字段均为 {@code null}（不进开仓 INSERT 路径）。</li>
 * </ul>
 */
public record TradingRiskDecision(
        boolean accepted,
        String rejectReason,
        BigDecimal fillPrice,
        BigDecimal margin,
        BigDecimal fee,
        BigDecimal openFeeRate,
        BigDecimal liquidationPrice,
        BigDecimal fxRate,
        BigDecimal feeFxRate,
        String quoteCurrency,
        BigDecimal marginInQuote,
        BigDecimal feeInQuote,
        BigDecimal mmRateAtOpen,
        Integer tierNoAtOpen
) {
}
