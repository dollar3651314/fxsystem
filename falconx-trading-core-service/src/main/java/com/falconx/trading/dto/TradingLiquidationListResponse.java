package com.falconx.trading.dto;

import java.util.List;

/**
 * 强平记录分页响应。
 */
public record TradingLiquidationListResponse(
        int page,
        int pageSize,
        long total,
        List<TradingLiquidationItemResponse> items
) {
}
