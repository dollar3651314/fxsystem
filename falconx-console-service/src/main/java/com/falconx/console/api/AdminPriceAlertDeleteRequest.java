package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;

public record AdminPriceAlertDeleteRequest(
        @NotBlank String reason
) {
}
