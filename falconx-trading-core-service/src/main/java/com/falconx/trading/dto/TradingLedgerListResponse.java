package com.falconx.trading.dto;

import java.util.List;

/**
 * 账本流水分页响应。
 */
public record TradingLedgerListResponse(
        int page,
        int pageSize,
        long total,
        List<TradingLedgerItemResponse> items
) {
}
