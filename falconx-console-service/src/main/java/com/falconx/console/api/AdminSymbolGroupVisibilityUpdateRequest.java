package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * STAGE-2-SYMBOL 三表管理：用户组可见性 upsert 请求。
 */
public record AdminSymbolGroupVisibilityUpdateRequest(
        @NotNull Integer visible,
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
