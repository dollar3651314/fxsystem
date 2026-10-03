package com.falconx.trading.dto;

import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * STAGE-3-PENDING-ORDER：修改挂单请求。
 *
 * <p>所有字段可选，传入 null 表示不修改。
 */
public record ModifyPendingOrderRequest(
        @Positive BigDecimal triggerPrice,
        @Positive BigDecimal limitPrice,
        @Positive BigDecimal quantity
) {
}
