package com.falconx.trading.dto;

import java.util.List;

/**
 * `Swap` 明细分页响应。
 */
public record TradingSwapSettlementListResponse(
        int page,
        int pageSize,
        long total,
        List<TradingSwapSettlementItemResponse> items
) {
}
