package com.falconx.trading.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminRiskActionDeactivateRequest(
        @NotBlank @Size(max = 200) String reason
) {
}
