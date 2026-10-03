package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminRiskConfigDeleteRequest(
        @NotBlank @Size(max = 200) String reason
) {
}
