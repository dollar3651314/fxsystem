package com.falconx.market.entity;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 市场交易时间例外规则。
 *
 * <p>该对象对应 `falconx_market.t_trading_hours_exception`，
 * 用于表达单个 `symbol` 在某一特殊日期上的人工覆盖规则。
 */
public record MarketTradingSessionException(
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
