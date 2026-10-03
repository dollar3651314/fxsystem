package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;

public record AdminKycRejectRequest(@NotBlank String reason) {
}
