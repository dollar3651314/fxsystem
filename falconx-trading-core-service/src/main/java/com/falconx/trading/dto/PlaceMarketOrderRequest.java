package com.falconx.trading.dto;

import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 市价单请求 DTO。
 *
 * <p>该对象承载外部调用 trading-core-service 时允许传入的最小字段集合。
 * 当前阶段只支持开仓型市价单，因此不包含复杂触发条件和高级订单选项。
 */
public record PlaceMarketOrderRequest(
        @NotBlank String symbol,
        @NotNull TradingOrderSide side,
        @NotNull @DecimalMin("0.00000001") BigDecimal quantity,
        @NotNull @DecimalMin("0.00000001") BigDecimal leverage,
        TradingMarginMode marginMode,
        @DecimalMin("0.00000001") BigDecimal takeProfitPrice,
        @DecimalMin("0.00000001") BigDecimal stopLossPrice,
        @NotBlank String clientOrderId
) {
}
