package com.falconx.console.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * Admin 市场节假日分页响应。
 */
public record AdminMarketHolidayListResponse(
        List<Item> items,
        long total,
        int page,
        int size
) {
    public record Item(
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
