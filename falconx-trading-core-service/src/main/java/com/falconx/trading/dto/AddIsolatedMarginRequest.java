package com.falconx.trading.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 追加逐仓保证金请求。
 */
public record AddIsolatedMarginRequest(
        @NotNull @DecimalMin("0.00000001") BigDecimal amount
) {
}
