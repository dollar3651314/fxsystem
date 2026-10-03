package com.falconx.market.entity;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 市场周交易时段规则。
 *
 * <p>该对象对应 `falconx_market.t_trading_hours`，
 * 用于表达某个 `symbol` 在一周内按星期重复生效的基础交易时段。
 */
public record MarketTradingSession(
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
