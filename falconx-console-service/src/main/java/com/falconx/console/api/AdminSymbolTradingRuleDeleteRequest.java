package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin 删除交易时段、特殊交易日或市场节假日请求。
 */
public record AdminSymbolTradingRuleDeleteRequest(
        @NotBlank @Size(min = 10, max = 500) String reason
) {
}
