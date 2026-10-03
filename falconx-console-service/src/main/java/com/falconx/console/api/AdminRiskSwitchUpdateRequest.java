package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminRiskSwitchUpdateRequest(
        @NotNull Boolean enabled,
        @NotBlank @Size(max = 256) String reason
) {
}
