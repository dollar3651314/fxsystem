package com.falconx.market.analytics;

import com.falconx.market.entity.KlineSnapshot;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.analytics.mapper.record.MarketKlineRecord;
import com.falconx.market.analytics.mapper.record.MarketQuoteTickRecord;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * market ClickHouse MyBatis 映射支持工具。
 *
 * <p>该工具统一处理 market 分析写入中的公共转换规则：
 *
 * <ul>
 *   <li>领域时间转 ClickHouse 使用的 UTC 本地时间</li>
 *   <li>标准报价对象转 `quote_tick` 记录对象</li>
 *   <li>K 线快照转 `kline` 记录对象</li>
 * </ul>
 */
final class MarketAnalyticsMybatisSupport {

    private MarketAnalyticsMybatisSupport() {
    }

    /**
     * 把领域时间转换为 ClickHouse 使用的 UTC 本地时间。
     *
     * @param value 领域时间
     * @return UTC 本地时间
     */
    static LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * 把标准报价转换为 ClickHouse `quote_tick` 记录对象。
     *
     * @param quote 标准报价
     * @return 报价记录对象
     */
    static MarketQuoteTickRecord toQuoteTickRecord(StandardQuote quote) {
        return new MarketQuoteTickRecord(
                quote.symbol(),
                quote.source(),
                quote.bid(),
                quote.ask(),
                quote.mid(),
                quote.mark(),
                toLocalDateTime(quote.ts())
        );
    }

    /**
     * 把 K 线快照转换为 ClickHouse `kline` 记录对象。
     *
     * @param snapshot K 线快照
     * @return K 线记录对象
     */
    static MarketKlineRecord toKlineRecord(KlineSnapshot snapshot) {
        return new MarketKlineRecord(
                snapshot.symbol(),
                snapshot.interval(),
                snapshot.open(),
                snapshot.high(),
                snapshot.low(),
                snapshot.close(),
                snapshot.volume(),
                toLocalDateTime(snapshot.openTime()),
                toLocalDateTime(snapshot.closeTime()),
                "market-service"
        );
    }
}
