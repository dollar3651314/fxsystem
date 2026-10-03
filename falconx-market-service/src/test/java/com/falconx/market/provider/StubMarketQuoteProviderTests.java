package com.falconx.market.provider;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StubMarketQuoteProviderTests {

    private final StubMarketQuoteProvider provider = new StubMarketQuoteProvider();

    @Test
    void shouldGenerateMarketShapedQuotesForLocalMetalProducts() {
        List<ExternalRawQuote> quotes = new ArrayList<>();

        provider.start(List.of("XAUUSD", "XAGUSD", "XPTUSD"), quotes::add);

        ExternalRawQuote xauUsd = quoteBySymbol(quotes, "XAUUSD");
        ExternalRawQuote xagUsd = quoteBySymbol(quotes, "XAGUSD");
        ExternalRawQuote xptUsd = quoteBySymbol(quotes, "XPTUSD");

        Assertions.assertTrue(xauUsd.bid().compareTo(new BigDecimal("4000")) > 0);
        Assertions.assertTrue(xauUsd.ask().compareTo(xauUsd.bid()) > 0);
        Assertions.assertTrue(xagUsd.bid().compareTo(new BigDecimal("40")) > 0);
        Assertions.assertTrue(xptUsd.bid().compareTo(new BigDecimal("1000")) > 0);
    }

    private ExternalRawQuote quoteBySymbol(List<ExternalRawQuote> quotes, String symbol) {
        return quotes.stream()
                .filter(quote -> quote.ticker().equals(symbol))
                .findFirst()
                .orElseThrow();
    }
}
