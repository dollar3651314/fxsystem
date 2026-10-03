package com.falconx.market.entity;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 市场节假日规则。
 *
 * <p>该对象对应 `falconx_market.t_trading_holiday`，
 * 用于表达按 `market_code` 统一生效的节假日休市、提前收盘和晚开盘规则。
 */
public record MarketTradingHoliday(
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
