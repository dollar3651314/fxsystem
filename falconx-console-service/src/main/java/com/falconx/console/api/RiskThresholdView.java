package com.falconx.console.api;

import java.math.BigDecimal;

/**
 * STAGE-14D3b Task2：风险阈值平台配置视图（StopOut / MarginCall）。
 *
 * <p>透传自 trading-core {@code GET /internal/v1/trading/console/config/platform-risk} 的
 * {@code stopOutLevel} / {@code marginCallLevel} 字段。
 */
public record RiskThresholdView(BigDecimal stopOutLevel, BigDecimal marginCallLevel) {
}
