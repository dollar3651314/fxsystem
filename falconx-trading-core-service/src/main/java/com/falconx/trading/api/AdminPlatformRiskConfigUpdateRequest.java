package com.falconx.trading.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * BBOOK-RISK-CONTROL-01：平台 hedge_threshold_usd 更新请求。
 */
public record AdminPlatformRiskConfigUpdateRequest(
        @NotNull @Positive BigDecimal hedgeThresholdUsd,
        String reason
) {
}
