package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * STAGE-2-SYMBOL: 暂停 / 恢复 symbol 请求（高风险，reason ≥10 字符）。
 */
public record AdminSymbolStatusRequest(
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
