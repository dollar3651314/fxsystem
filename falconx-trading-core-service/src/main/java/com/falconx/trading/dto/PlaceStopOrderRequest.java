package com.falconx.trading.dto;

import com.falconx.trading.entity.TradingMarginMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * STAGE-3-PENDING-ORDER：STOP / STOP_LIMIT 挂单请求。
 *
 * <p>STOP：limitPrice = null；触发后转市价。
 * <p>STOP_LIMIT：limitPrice 非空；触发后挂 LIMIT。
 */
public record PlaceStopOrderRequest(
        @NotBlank String symbol,
        @NotBlank String side,
        @NotNull @Positive BigDecimal quantity,
        @NotNull @Positive BigDecimal stopPrice,
        @Positive BigDecimal limitPrice,
        @NotNull @Positive BigDecimal leverage,
        TradingMarginMode marginMode,
        @NotBlank String clientOrderId
) {
}
