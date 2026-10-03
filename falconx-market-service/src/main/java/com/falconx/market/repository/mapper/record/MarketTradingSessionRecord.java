package com.falconx.market.repository.mapper.record;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * `t_trading_hours` 持久化记录。
 */
public record MarketTradingSessionRecord(
        Long id,
        String symbol,
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
