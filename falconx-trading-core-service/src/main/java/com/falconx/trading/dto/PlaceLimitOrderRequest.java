package com.falconx.trading.dto;

import com.falconx.trading.entity.TradingMarginMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * STAGE-3-PENDING-ORDER：LIMIT 挂单请求。
 */
public record PlaceLimitOrderRequest(
        @NotBlank String symbol,
        @NotBlank String side,
        @NotNull @Positive BigDecimal quantity,
        @NotNull @Positive BigDecimal limitPrice,
        @NotNull @Positive BigDecimal leverage,
        TradingMarginMode marginMode,
        @NotBlank String clientOrderId
) {
}
