package com.falconx.trading.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record AdminRiskConfigCreateRequest(
        @NotBlank String symbol,
        String marketCode,
        @NotNull BigDecimal maxPositionPerUser,
        @NotNull BigDecimal maxPositionTotal,
        @NotNull BigDecimal maintenanceMarginRate,
        @NotNull Integer maxLeverage,
        @NotNull BigDecimal hedgeThresholdUsd,
        @NotBlank @Size(max = 200) String reason
) {
}
