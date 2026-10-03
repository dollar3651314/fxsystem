package com.falconx.trading.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理端风控开关切换请求体（trading-core internal RPC）。
 */
public record AdminRiskSwitchUpdateRequest(
        @NotNull Boolean enabled,
        @NotBlank @Size(max = 256) String reason
) {
}
