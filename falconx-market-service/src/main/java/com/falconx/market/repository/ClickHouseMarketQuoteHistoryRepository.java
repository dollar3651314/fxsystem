package com.falconx.market.repository;

import com.falconx.market.analytics.mapper.MarketQuoteTickMapper;
import com.falconx.market.analytics.mapper.record.MarketQuoteTickRecord;
import com.falconx.market.entity.StandardQuote;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * ClickHouse 报价历史查询实现。
 */
@Repository
@Profile("!stub")
public class ClickHouseMarketQuoteHistoryRepository implements MarketQuoteHistoryRepository {

    private final MarketQuoteTickMapper marketQuoteTickMapper;

    public ClickHouseMarketQuoteHistoryRepository(MarketQuoteTickMapper marketQuoteTickMapper) {
        this.marketQuoteTickMapper = marketQuoteTickMapper;
    }

    @Override
    public Optional<StandardQuote> findLatestBySymbol(String symbol) {
        return Optional.ofNullable(marketQuoteTickMapper.selectLatestBySymbol(symbol))
                .map(this::toReferenceQuote);
    }

    @Override
    public List<StandardQuote> findRecentBySymbol(String symbol, int limit) {
        return marketQuoteTickMapper.selectRecentBySymbol(symbol, limit).stream()
                .map(this::toHistoricalQuote)
                .toList();
    }

    private StandardQuote toReferenceQuote(MarketQuoteTickRecord record) {
        return new StandardQuote(
                record.symbol(),
                record.bidPrice(),
                record.askPrice(),
                record.midPrice(),
                record.markPrice(),
                OffsetDateTime.of(record.eventTime(), ZoneOffset.UTC),
                record.source(),
                true
        );
    }

    private StandardQuote toHistoricalQuote(MarketQuoteTickRecord record) {
        return new StandardQuote(
                record.symbol(),
                record.bidPrice(),
                record.askPrice(),
                record.midPrice(),
                record.markPrice(),
                OffsetDateTime.of(record.eventTime(), ZoneOffset.UTC),
                record.source(),
                false
        );
    }
}
