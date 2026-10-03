package com.falconx.market.service.impl;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.MarketSymbolQuoteMapping;
import com.falconx.market.provider.ExternalRawQuote;
import com.falconx.market.repository.MarketSymbolQuoteMappingRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DefaultMarketQuoteMappingServiceTests {

    @Test
    void shouldFanOutSourceQuoteToConfiguredPlatformSymbols() {
        OffsetDateTime now = OffsetDateTime.now();
        DefaultMarketQuoteMappingService service = new DefaultMarketQuoteMappingService(
                new FixedMappingRepository(List.of(
                        new MarketSymbolQuoteMapping(
                                "XAU100",
                                "LP",
                                "GODSA",
                                "XAUUSD",
                                new BigDecimal("100.00000000"),
                                new BigDecimal("0.00000000"),
                                new BigDecimal("1.00000000"),
                                1,
                                1
                        ),
                        new MarketSymbolQuoteMapping(
                                "AAAUSD",
                                "LP",
                                "GODSA",
                                "XAUUSD",
                                new BigDecimal("1.00000000"),
                                new BigDecimal("0.10000000"),
                                new BigDecimal("0.20000000"),
                                1,
                                1
                        )
                )),
                new MarketServiceProperties()
        );
        service.refreshMappings();

        List<ExternalRawQuote> mappedQuotes = service.mapToPlatformQuotes(new ExternalRawQuote(
                "XAUUSD",
                new BigDecimal("73.62000000"),
                new BigDecimal("73.65000000"),
                now,
                "TM_QUOTE"
        ));

        Assertions.assertEquals(List.of("XAUUSD"), service.sourceSymbols());
        Assertions.assertEquals(2, mappedQuotes.size());
        Map<String, ExternalRawQuote> mappedBySymbol = mappedQuotes.stream()
                .collect(Collectors.toMap(ExternalRawQuote::ticker, quote -> quote));
        Assertions.assertEquals(new BigDecimal("73.7200000000000000"), mappedBySymbol.get("AAAUSD").bid());
        Assertions.assertEquals(new BigDecimal("73.8500000000000000"), mappedBySymbol.get("AAAUSD").ask());
        Assertions.assertEquals(new BigDecimal("7362.0000000000000000"), mappedBySymbol.get("XAU100").bid());
        Assertions.assertEquals(new BigDecimal("7366.0000000000000000"), mappedBySymbol.get("XAU100").ask());
    }

    @Test
    void shouldOnlyUseMappingsForConfiguredLpCodeWhenSourceSymbolIsSame() {
        OffsetDateTime now = OffsetDateTime.now();
        MarketServiceProperties properties = new MarketServiceProperties();
        properties.getLp().setCode("LP2");
        DefaultMarketQuoteMappingService service = new DefaultMarketQuoteMappingService(
                new FixedMappingRepository(List.of(
                        new MarketSymbolQuoteMapping(
                                "GODSA-XAU",
                                "LP",
                                "GODSA",
                                "XAUUSD",
                                BigDecimal.ONE,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                1,
                                1
                        ),
                        new MarketSymbolQuoteMapping(
                                "LP2-XAU",
                                "LP",
                                "LP2",
                                "XAUUSD",
                                new BigDecimal("2.00000000"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                1,
                                1
                        )
                )),
                properties
        );
        service.refreshMappings();

        List<ExternalRawQuote> mappedQuotes = service.mapToPlatformQuotes(new ExternalRawQuote(
                "XAUUSD",
                new BigDecimal("10.00000000"),
                new BigDecimal("11.00000000"),
                now,
                "TM_QUOTE"
        ));

        Assertions.assertEquals(List.of("XAUUSD"), service.sourceSymbols());
        Assertions.assertEquals(1, mappedQuotes.size());
        Assertions.assertEquals("LP2-XAU", mappedQuotes.getFirst().ticker());
        Assertions.assertEquals(new BigDecimal("20.0000000000000000"), mappedQuotes.getFirst().bid());
    }

    private record FixedMappingRepository(List<MarketSymbolQuoteMapping> mappings)
            implements MarketSymbolQuoteMappingRepository {

        @Override
        public List<MarketSymbolQuoteMapping> findAllLpSubscribedMappings() {
            return mappings;
        }
    }
}
