package com.falconx.market.analytics.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * ClickHouse K 线记录对象。
 *
 * @param symbol 品种
 * @param intervalType 周期
 * @param openPrice 开盘价
 * @param highPrice 最高价
 * @param lowPrice 最低价
 * @param closePrice 收盘价
 * @param volume 成交量
 * @param openTime 开盘时间
 * @param closeTime 收盘时间
 * @param source 写入来源
 */
public record MarketKlineRecord(
        String symbol,
        String intervalType,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal closePrice,
        BigDecimal volume,
        LocalDateTime openTime,
        LocalDateTime closeTime,
        String source
) {

    public long openTimeEpochMillis() {
        return openTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    public long closeTimeEpochMillis() {
        return closeTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
