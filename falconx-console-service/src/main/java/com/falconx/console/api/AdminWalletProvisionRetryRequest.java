package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;

public record AdminWalletProvisionRetryRequest(
        @NotBlank String reason
) {
}
