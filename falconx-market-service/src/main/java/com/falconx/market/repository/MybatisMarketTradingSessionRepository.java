package com.falconx.market.repository;

import com.falconx.market.entity.MarketTradingSession;
import com.falconx.market.repository.mapper.MarketTradingSessionMapper;
import com.falconx.market.repository.mapper.record.MarketTradingSessionRecord;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 市场周交易时段仓储的 MyBatis 实现。
 */
@Repository
public class MybatisMarketTradingSessionRepository implements MarketTradingSessionRepository {

    private final MarketTradingSessionMapper marketTradingSessionMapper;

    public MybatisMarketTradingSessionRepository(MarketTradingSessionMapper marketTradingSessionMapper) {
        this.marketTradingSessionMapper = marketTradingSessionMapper;
    }

    @Override
    public List<MarketTradingSession> findAllEnabled() {
        return marketTradingSessionMapper.selectAllEnabled().stream()
                .map(this::toDomain)
                .toList();
    }

    private MarketTradingSession toDomain(MarketTradingSessionRecord record) {
        return new MarketTradingSession(
                record.id(),
                record.symbol(),
                record.dayOfWeek(),
                record.sessionNo(),
                record.openTime(),
                record.closeTime(),
                record.timezone(),
                record.enabled(),
                record.effectiveFrom(),
                record.effectiveTo()
        );
    }
}
