package com.falconx.trading.service.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 交易时间快照。
 *
 * <p>该对象由 `market-service` 写入 Redis，`trading-core-service` 只读不写，
 * 用于下单前做交易时间管理方案 B 的本地判定。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradingScheduleSnapshot(
        String symbol,
        String marketCode,
        List<TradingSessionWindow> sessions,
        List<TradingHoursExceptionRule> exceptions,
        List<TradingHolidayRule> holidays,
        OffsetDateTime refreshedAt
) {
}
