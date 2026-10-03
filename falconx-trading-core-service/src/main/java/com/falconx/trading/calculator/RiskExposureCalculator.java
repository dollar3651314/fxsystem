package com.falconx.trading.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * 净敞口估值计算器。
 *
 * <p>该组件把 B-book 风险观测里会反复出现的数量聚合与美元口径换算收口成可单测逻辑，
 * 避免把 `(多头 - 空头) * mark_price` 公式散落在服务实现和测试里。
 */
@Component
public class RiskExposureCalculator {

    /**
     * 计算数量口径净敞口。
     *
     * @param totalLongQty 多头总量
     * @param totalShortQty 空头总量
     * @return 净敞口 = 多头 - 空头
     */
    public BigDecimal calculateNetExposure(BigDecimal totalLongQty, BigDecimal totalShortQty) {
        return totalLongQty.subtract(totalShortQty).setScale(8, RoundingMode.DOWN);
    }

    /**
     * 按已有净敞口和最新标记价换算美元敞口（历史口径，等价 fx=1）。
     *
     * <p><b>注意</b>：{@code netExposure × markPrice} 的结果是该 symbol 的<b>计价币(QC)金额</b>，
     * 仅当 QC=USD 时才是真 USD。多币种 USD 化（2026-06-03）后，正式链路一律走
     * {@link #calculateNetExposureUsd(BigDecimal, BigDecimal, BigDecimal)} 传入 QC→USD 汇率；
     * 本重载保留给 QC 未知时的降级口径（与历史行为一致）。
     */
    public BigDecimal calculateNetExposureUsd(BigDecimal netExposure, BigDecimal markPrice) {
        return calculateNetExposureUsd(netExposure, markPrice, BigDecimal.ONE);
    }

    /**
     * 真 USD 口径净敞口：净敞口 × 标记价(QC) × fx(QC→USD)。
     *
     * <p>多币种 USD 化（2026-06-03，§1 下一步 (A)）：此前 {@code netExposure × markPrice}
     * 对非 USD 计价品种（AUDCAD→CAD、AUDJPY→JPY 等）得到的是计价币口径，
     * 与对冲阈值 hedgeThresholdUsd（真 USD）比较会按汇率倍数误判（JPY 系 ~160 倍）。
     *
     * @param fxRate QC→USD 汇率；{@code null} 视为 1（QC 缺失 / FX 不可用降级，caller 负责告警）
     */
    public BigDecimal calculateNetExposureUsd(BigDecimal netExposure, BigDecimal markPrice, BigDecimal fxRate) {
        BigDecimal fx = fxRate == null ? BigDecimal.ONE : fxRate;
        return netExposure.multiply(markPrice).multiply(fx).setScale(8, RoundingMode.DOWN);
    }
}
