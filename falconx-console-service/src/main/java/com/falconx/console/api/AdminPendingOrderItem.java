package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-3-PENDING-ORDER：管理端挂单列表项。
 */
public record AdminPendingOrderItem(
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
        /** symbol 价格显示精度（透传 trading-core SymbolSpec.pricePrecision）；过渡期可能为 null。 */
        Integer pricePrecision
) {
}
