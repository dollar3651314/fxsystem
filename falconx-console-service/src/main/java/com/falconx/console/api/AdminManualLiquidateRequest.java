package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminManualLiquidateRequest(
        @NotBlank @Size(max = 256) String reason
) {
}
