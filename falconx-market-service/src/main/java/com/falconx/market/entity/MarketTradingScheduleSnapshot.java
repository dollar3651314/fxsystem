package com.falconx.market.entity;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 交易时间快照。
 *
 * <p>该对象不是 MySQL owner 表，而是 `market-service` 写入 Redis 的运行时快照，
 * 供 `trading-core-service` 在下单前直接读取而不跨服务查库。
 */
public record MarketTradingScheduleSnapshot(
        String symbol,
        String marketCode,
        List<MarketTradingSession> sessions,
        List<MarketTradingSessionException> exceptions,
        List<MarketTradingHoliday> holidays,
        OffsetDateTime refreshedAt
) {
}
