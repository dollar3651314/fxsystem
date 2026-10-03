package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminRiskActionActivateRequest(
        String symbol,
        @NotNull String actionType,
        @NotBlank @Size(max = 200) String reason
) {
}
