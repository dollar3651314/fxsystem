package com.falconx.trading.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-3-PENDING-ORDER：挂单返回项。
 */
public record TradingPendingOrderItemResponse(
        Long id,
        String orderNo,
        String symbol,
        String orderType,
        String side,
        BigDecimal quantity,
        BigDecimal triggerPrice,
        BigDecimal limitPrice,
        BigDecimal leverage,
        String marginMode,
        BigDecimal frozenMargin,
        BigDecimal frozenFee,
        String status,
        Long parentPositionId,
        String triggerKind,
        String clientOrderId,
        Long triggeredOrderId,
        OffsetDateTime triggeredAt,
        OffsetDateTime cancelledAt,
        String cancelReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        /** symbol 价格显示精度（SymbolSpec.pricePrecision）；用户路径填 null（前端自有精度 hook），admin 路径填充供管理端格式化。 */
        Integer pricePrecision
) {
}
