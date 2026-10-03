package com.falconx.trading.dto;

import java.util.List;

/**
 * STAGE-3-PENDING-ORDER：挂单分页响应。
 */
public record TradingPendingOrderListResponse(
        int page,
        int pageSize,
        long total,
        List<TradingPendingOrderItemResponse> items
) {
}
