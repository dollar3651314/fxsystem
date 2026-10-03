package com.falconx.trading.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * STAGE-4-PRICE-ALERT：创建告警请求。
 *
 * <p>{@code direction} 可选：传 null 时按 targetPrice vs currentMark 自动推导。
 */
public record CreatePriceAlertRequest(
        @NotBlank String symbol,
        String direction,  // ABOVE / BELOW / null
        @NotNull @Positive BigDecimal targetPrice,
        @Size(max = 200) String note
) {
}
