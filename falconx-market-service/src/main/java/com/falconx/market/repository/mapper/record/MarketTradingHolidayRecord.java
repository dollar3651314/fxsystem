package com.falconx.market.repository.mapper.record;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * `t_trading_holiday` 持久化记录。
 */
public record MarketTradingHolidayRecord(
        Long id,
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
