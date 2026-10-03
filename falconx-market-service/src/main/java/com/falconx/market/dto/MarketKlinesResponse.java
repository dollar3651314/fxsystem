package com.falconx.market.dto;

import java.util.List;

/**
 * 北向 K 线查询响应。
 */
public record MarketKlinesResponse(
        List<MarketKlineItemResponse> klines
) {
}
