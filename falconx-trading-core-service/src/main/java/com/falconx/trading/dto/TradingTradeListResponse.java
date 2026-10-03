package com.falconx.trading.dto;

import java.util.List;

/**
 * 成交分页响应。
 */
public record TradingTradeListResponse(
        int page,
        int pageSize,
        long total,
        List<TradingTradeItemResponse> items
) {
}
