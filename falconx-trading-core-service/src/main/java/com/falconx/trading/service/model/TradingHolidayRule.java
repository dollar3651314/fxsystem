package com.falconx.trading.service.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 交易节假日规则。
 *
 * <p>该对象按 `market_code` 生效，
 * 用于统一表达节假日休市、提前收盘和晚开盘规则。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradingHolidayRule(
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
