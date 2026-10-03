package com.falconx.trading.dto;

import java.util.List;

/**
 * 持仓分页响应。
 */
public record TradingPositionListResponse(
        int page,
        int pageSize,
        long total,
        List<TradingPositionItemResponse> items
) {
}
