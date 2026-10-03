package com.falconx.console.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * STAGE-2-SYMBOL: platform symbol 交易时段响应（含 sessions / exceptions / market holidays）。
 */
public record AdminTradingHoursResponse(
        String symbol,
        String marketCode,
        List<AdminSymbolDetailResponse.TradingSessionItem> sessions,
        List<ExceptionItem> exceptions,
        List<HolidayItem> holidays
) {

    public record ExceptionItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String symbol,
            LocalDate tradeDate,
            int exceptionType,
            Integer sessionNo,
            LocalTime openTime,
            LocalTime closeTime,
            String timezone,
            String reason
    ) {
    }

    public record HolidayItem(
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            String marketCode,
            LocalDate holidayDate,
            int holidayType,
            LocalTime openTime,
            LocalTime closeTime,
            String timezone,
            String holidayName,
            String countryCode
    ) {
    }
}
