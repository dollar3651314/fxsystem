package com.falconx.trading.service.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 交易时间周规则窗口。
 *
 * <p>该对象对应 `market-service` 写入 Redis 快照中的基础 session 规则，
 * 用于 `trading-core-service` 在下单前做交易时间判定。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradingSessionWindow(
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
