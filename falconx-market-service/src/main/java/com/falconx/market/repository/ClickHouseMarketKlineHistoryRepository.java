package com.falconx.market.repository;

import com.falconx.market.analytics.mapper.MarketKlineMapper;
import com.falconx.market.analytics.mapper.record.MarketKlineRecord;
import com.falconx.market.entity.KlineSnapshot;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * ClickHouse K 线历史仓储实现。
 */
@Repository
public class ClickHouseMarketKlineHistoryRepository implements MarketKlineHistoryRepository {

    private final MarketKlineMapper marketKlineMapper;

    public ClickHouseMarketKlineHistoryRepository(MarketKlineMapper marketKlineMapper) {
        this.marketKlineMapper = marketKlineMapper;
    }

    @Override
    public List<KlineSnapshot> findRecentKlines(String symbol, String interval, int limit) {
        return marketKlineMapper.selectRecentKlines(symbol, interval, limit).stream()
                .map(this::toSnapshot)
                .toList();
    }

    private KlineSnapshot toSnapshot(MarketKlineRecord record) {
        return new KlineSnapshot(
                record.symbol(),
                record.intervalType(),
                record.openPrice(),
                record.highPrice(),
                record.lowPrice(),
                record.closePrice(),
                record.volume(),
                OffsetDateTime.of(record.openTime(), ZoneOffset.UTC),
                OffsetDateTime.of(record.closeTime(), ZoneOffset.UTC),
                true
        );
    }
}
