package com.falconx.market.dto;

import java.util.List;

/**
 * 首页品种列表响应。
 */
public record MarketSymbolsResponse(
        List<MarketSymbolListItemResponse> symbols
) {
}
