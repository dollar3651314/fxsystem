package com.falconx.market.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 北向 K 线查询响应项。
 */
public record MarketKlineItemResponse(
        String symbol,
        String interval,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        OffsetDateTime openTime,
        OffsetDateTime closeTime,
        boolean isFinal
) {
}
