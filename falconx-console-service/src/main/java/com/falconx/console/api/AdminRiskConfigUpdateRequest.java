package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record AdminRiskConfigUpdateRequest(
        @NotNull BigDecimal maxPositionPerUser,
        @NotNull BigDecimal maxPositionTotal,
        @NotNull Integer maxLeverage,
        @NotNull BigDecimal hedgeThresholdUsd,
        @NotBlank @Size(max = 200) String reason
) {
}
