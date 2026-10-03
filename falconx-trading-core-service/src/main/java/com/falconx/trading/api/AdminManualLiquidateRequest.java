package com.falconx.trading.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理端手动强平请求体（trading-core internal RPC）。
 */
public record AdminManualLiquidateRequest(
        @NotBlank @Size(max = 256) String reason
) {
}
