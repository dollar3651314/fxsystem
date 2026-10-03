package com.falconx.console.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * BBOOK-RISK-CONTROL-01：平台总敞口阈值更新请求。
 */
public record AdminPlatformRiskConfigUpdateRequest(
        @NotNull @Positive BigDecimal hedgeThresholdUsd,
        String reason
) {
}
