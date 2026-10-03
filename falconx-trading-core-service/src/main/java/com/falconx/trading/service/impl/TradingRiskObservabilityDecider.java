package com.falconx.trading.service.impl;

import com.falconx.trading.calculator.RiskExposureCalculator;
import com.falconx.trading.entity.TradingHedgeLog;
import com.falconx.trading.entity.TradingHedgeLogStatus;
import com.falconx.trading.entity.TradingRiskExposure;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * B-book 风险观测判断器。
 *
 * <p>该组件只负责纯业务判断，不关心日志、仓储和事件发布，
 * 便于用单元测试覆盖净 USD 敞口换算与阈值状态机。
 */
@Component
class TradingRiskObservabilityDecider {

    private final RiskExposureCalculator riskExposureCalculator;

    TradingRiskObservabilityDecider(RiskExposureCalculator riskExposureCalculator) {
        this.riskExposureCalculator = riskExposureCalculator;
    }

    /**
     * @param fxRate QC→USD 汇率（多币种 USD 化，2026-06-03）；{@code null} 视为 1（QC 缺失/FX 不可用降级）
     */
    TradingRiskObservabilityDecision evaluate(TradingRiskExposure exposure,
                                              BigDecimal hedgeThresholdUsd,
                                              BigDecimal markPrice,
                                              BigDecimal fxRate,
                                              TradingHedgeLog latestLog) {
        BigDecimal netExposureUsd = riskExposureCalculator.calculateNetExposureUsd(
                riskExposureCalculator.calculateNetExposure(exposure.totalLongQty(), exposure.totalShortQty()),
                markPrice,
                fxRate
        );
        if (hedgeThresholdUsd == null || hedgeThresholdUsd.signum() <= 0) {
            return new TradingRiskObservabilityDecision(null, netExposureUsd, false, false);
        }

        boolean activeAlert = latestLog != null && latestLog.actionStatus() == TradingHedgeLogStatus.ALERT_ONLY;
        boolean breached = exposure.netExposure().signum() != 0
                && netExposureUsd.abs().compareTo(hedgeThresholdUsd) >= 0;
        if (breached) {
            boolean directionChanged = latestLog != null
                    && latestLog.netExposureUsd() != null
                    && latestLog.netExposureUsd().signum() != netExposureUsd.signum();
            if (!activeAlert || directionChanged) {
                return new TradingRiskObservabilityDecision(TradingHedgeLogStatus.ALERT_ONLY, netExposureUsd, true, true);
            }
            return new TradingRiskObservabilityDecision(null, netExposureUsd, false, true);
        }

        if (activeAlert) {
            return new TradingRiskObservabilityDecision(TradingHedgeLogStatus.RECOVERED, netExposureUsd, false, false);
        }
        return new TradingRiskObservabilityDecision(null, netExposureUsd, false, false);
    }
}
