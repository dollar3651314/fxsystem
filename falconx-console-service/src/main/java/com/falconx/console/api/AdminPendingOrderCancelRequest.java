package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;

public record AdminPendingOrderCancelRequest(
        @NotBlank String reason
) {
}
