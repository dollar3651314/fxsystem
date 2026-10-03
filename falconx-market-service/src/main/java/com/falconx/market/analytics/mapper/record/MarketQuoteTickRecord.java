package com.falconx.market.analytics.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * ClickHouse 报价 Tick 记录对象。
 *
 * @param symbol 品种
 * @param source 报价来源
 * @param bidPrice 买一价
 * @param askPrice 卖一价
 * @param midPrice 中间价
 * @param markPrice 标记价
 * @param eventTime 事件时间
 */
public record MarketQuoteTickRecord(
        String symbol,
        String source,
        BigDecimal bidPrice,
        BigDecimal askPrice,
        BigDecimal midPrice,
        BigDecimal markPrice,
        LocalDateTime eventTime
) {

    public long eventTimeEpochMillis() {
        return eventTime.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
