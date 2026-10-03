package com.falconx.trading.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 订单查询项。
 */
public record TradingOrderItemResponse(
        Long orderId,
        String orderNo,
        String symbol,
        String side,
        String orderType,
        BigDecimal quantity,
        BigDecimal requestedPrice,
        BigDecimal filledPrice,
        BigDecimal leverage,
        BigDecimal margin,
        BigDecimal fee,
        String clientOrderId,
        String status,
        String rejectReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
