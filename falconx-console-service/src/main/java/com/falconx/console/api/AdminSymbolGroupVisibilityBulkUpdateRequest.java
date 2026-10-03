package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * STAGE-2-SYMBOL 三表管理：用户组可见性批量 upsert 请求。
 */
public record AdminSymbolGroupVisibilityBulkUpdateRequest(
        @NotEmpty @Size(max = 500) List<@NotBlank @Size(max = 32) String> symbols,
        @NotNull Integer visible,
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
