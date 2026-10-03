package com.falconx.market.repository;

import com.falconx.market.entity.StandardQuote;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("stub")
public class StubMarketLatestQuoteRepository implements MarketLatestQuoteRepository {

    private final Map<String, StandardQuote> store = new HashMap<>();

    @Override
    public void save(StandardQuote quote) {
        store.put(quote.symbol(), quote);
    }

    @Override
    public Optional<StandardQuote> findBySymbol(String symbol) {
        return Optional.ofNullable(store.get(symbol));
    }

    @Override
    public Map<String, StandardQuote> findBySymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return Map.of();
        Map<String, StandardQuote> out = new LinkedHashMap<>(symbols.size());
        for (String s : symbols) {
            StandardQuote q = store.get(s);
            if (q != null) out.put(s, q);
        }
        return out;
    }
}
