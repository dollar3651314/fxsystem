package com.falconx.trading.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Swap 资金费率聚合摘要。
 *
 * <p>用于持仓维度 / 账户维度两种聚合场景：
 * <ul>
 *   <li>持仓维度：filter by {@code reference_no LIKE 'swap:{positionId}:%'}</li>
 *   <li>账户维度：filter by {@code user_id} +（可选）时间窗 {@code [from, to]}</li>
 * </ul>
 *
 * <p>{@code totalCharge} / {@code totalIncome} 都是 ledger 中 amount 字段的 SUM（恒为正值的绝对量），
 * 由 {@code biz_type=6(SWAP_CHARGE)} 与 {@code 7(SWAP_INCOME)} 分组而来。
 * {@code net} = {@code totalIncome - totalCharge}，正=净收入、负=净支付。
 *
 * <p>无任何 Swap 命中时全部数字为 0、时间为 null（前端按 0 / "—" 显示）。
 */
public record TradingSwapSummaryResponse(
        BigDecimal totalCharge,
        BigDecimal totalIncome,
        BigDecimal net,
        int chargeCount,
        int incomeCount,
        OffsetDateTime firstAt,
        OffsetDateTime lastAt
) {
    public static TradingSwapSummaryResponse empty() {
        return new TradingSwapSummaryResponse(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, null, null);
    }
}
