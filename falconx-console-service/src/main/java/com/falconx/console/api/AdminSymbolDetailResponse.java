package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * STAGE-2-SYMBOL: LP 源 symbol 详情响应。
 *
 * <p>V16 起 currentSwapRate / sessions 不再挂 source symbol，管理端按
 * {@code t_symbol_quote_mapping.platform_symbol} 单独查询 Swap 与交易时段。
 */
public record AdminSymbolDetailResponse(
        AdminSymbolListResponse.Item symbol,
        SwapRateItem currentSwapRate,
        List<TradingSessionItem> sessions
) {

    public record SwapRateItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String symbol,
            BigDecimal longRate,
            BigDecimal shortRate,
            LocalTime rolloverTime,
            LocalDate effectiveFrom,
            LocalDateTime createdAt
    ) {
    }

    public record TradingSessionItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            int dayOfWeek,
            int sessionNo,
            LocalTime openTime,
            LocalTime closeTime,
            String timezone,
            boolean enabled,
            LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {
    }
}
