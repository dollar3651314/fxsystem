package com.falconx.market.repository;

import com.falconx.market.entity.StandardQuote;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("stub")
public class StubMarketQuoteHistoryRepository implements MarketQuoteHistoryRepository {

    @Override
    public Optional<StandardQuote> findLatestBySymbol(String symbol) {
        return Optional.empty();
    }

    @Override
    public List<StandardQuote> findRecentBySymbol(String symbol, int limit) {
        return List.of();
    }
}
