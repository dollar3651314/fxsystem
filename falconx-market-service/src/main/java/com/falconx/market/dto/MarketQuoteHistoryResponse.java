package com.falconx.market.dto;

import java.util.List;

/**
 * 北向历史报价 Tick 查询响应。
 */
public record MarketQuoteHistoryResponse(
        List<MarketQuoteResponse> quotes
) {
}
