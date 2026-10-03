package com.falconx.market.repository;

import com.falconx.market.entity.MarketTradingSessionException;
import com.falconx.market.repository.mapper.MarketTradingSessionExceptionMapper;
import com.falconx.market.repository.mapper.record.MarketTradingSessionExceptionRecord;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 市场交易时间例外规则仓储的 MyBatis 实现。
 */
@Repository
public class MybatisMarketTradingSessionExceptionRepository implements MarketTradingSessionExceptionRepository {

    private final MarketTradingSessionExceptionMapper marketTradingSessionExceptionMapper;

    public MybatisMarketTradingSessionExceptionRepository(
            MarketTradingSessionExceptionMapper marketTradingSessionExceptionMapper
    ) {
        this.marketTradingSessionExceptionMapper = marketTradingSessionExceptionMapper;
    }

    @Override
    public List<MarketTradingSessionException> findAll() {
        return marketTradingSessionExceptionMapper.selectAll().stream()
                .map(this::toDomain)
                .toList();
    }

    private MarketTradingSessionException toDomain(MarketTradingSessionExceptionRecord record) {
        return new MarketTradingSessionException(
                record.id(),
                record.symbol(),
                record.tradeDate(),
                record.exceptionType(),
                record.sessionNo(),
                record.openTime(),
                record.closeTime(),
                record.timezone(),
                record.reason()
        );
    }
}
