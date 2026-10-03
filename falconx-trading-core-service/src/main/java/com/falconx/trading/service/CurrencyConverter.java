package com.falconx.trading.service;

import java.math.BigDecimal;

/**
 * STAGE-14B Task 4：trading-core 货币换算器。
 *
 * <p>薄包装 {@link FxRateService#queryRate}，把一笔金额从 {@code from} 币种换算为 {@code to} 币种。
 * 供算法层（Margin / PnL / Fee / Swap）注入使用。
 *
 * <p>精度与 market-side {@code DECIMAL(24,8)} 一致：结果统一保留 8 位小数 HALF_UP。
 */
public interface CurrencyConverter {

    /**
     * 把 {@code amount} 从 {@code from} 币种换算为 {@code to} 币种。
     *
     * <p>行为约定：
     * <ul>
     *   <li>{@code from.equals(to)}：短路直接返回原始 {@code amount}（不查询汇率，不改变精度）。</li>
     *   <li>汇率可查：返回 {@code amount × rate}，结果保留 8 位小数 HALF_UP。</li>
     *   <li>汇率不可用（FX 路径缺失 / market 行情未到）：<b>返回 {@code null} 并打 WARN 日志</b>，
     *       由调用方（Task 6-9 算法层）检测 null 并抛出 30073 FX_RATE_UNAVAILABLE。
     *       不抛异常，不退化为 {@code amount × 1}，避免静默返回错误金额。</li>
     * </ul>
     *
     * @param amount 待换算金额，非空
     * @param from   源币种，非空，如 "EUR"
     * @param to     目标币种，非空，如 "USD"
     * @return 换算后金额（8 位 HALF_UP）；同币种时返回原始 {@code amount}；汇率不可用时返回 {@code null}
     * @throws NullPointerException 任一入参为 null
     */
    BigDecimal convert(BigDecimal amount, String from, String to);
}
