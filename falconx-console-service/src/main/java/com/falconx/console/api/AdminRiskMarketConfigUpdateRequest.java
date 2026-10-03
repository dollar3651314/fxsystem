package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record AdminRiskMarketConfigUpdateRequest(
        @NotNull BigDecimal concentrationThresholdUsd,
        @NotNull Boolean isEnabled,
        @NotBlank @Size(max = 200) String reason
) {
}
