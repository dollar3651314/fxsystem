package com.falconx.market.repository.mapper.record;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * `t_trading_hours_exception` 持久化记录。
 */
public record MarketTradingSessionExceptionRecord(
        Long id,
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
