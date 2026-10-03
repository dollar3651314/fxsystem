package com.falconx.console.api;

/**
 * STAGE-14D3b Task2：保证金模式平台配置视图（冷静期）。
 *
 * <p>透传自 trading-core {@code GET /internal/v1/trading/console/config/platform-risk} 的
 * {@code coolingPeriodSeconds} 字段。
 */
public record MarginModeConfigView(int coolingPeriodSeconds) {
}
