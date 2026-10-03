package com.falconx.trading.dto;

import java.util.List;

/**
 * 订单分页响应。
 */
public record TradingOrderListResponse(
        int page,
        int pageSize,
        long total,
        List<TradingOrderItemResponse> items
) {
}
