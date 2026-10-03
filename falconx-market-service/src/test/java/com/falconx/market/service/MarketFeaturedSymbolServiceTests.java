package com.falconx.market.service;

import com.falconx.market.entity.MarketFeaturedSymbol;
import com.falconx.market.repository.MarketFeaturedSymbolRepository;
import com.falconx.market.service.MarketFeaturedSymbolService.FeaturedInput;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 跑马灯热门产品配置领域服务测试。
 */
class MarketFeaturedSymbolServiceTests {

    /** 内存桩仓储，捕获 replaceAll 入参。 */
    private static final class StubRepository implements MarketFeaturedSymbolRepository {
        private List<MarketFeaturedSymbol> stored = List.of();

        @Override
        public List<MarketFeaturedSymbol> findAllOrdered() {
            return stored;
        }

        @Override
        public void replaceAll(List<MarketFeaturedSymbol> items) {
            this.stored = items;
        }
    }

    @Test
    void replaceAllAssignsSortOrderByIndexAndMapsEnabled() {
        StubRepository repo = new StubRepository();
        MarketFeaturedSymbolService service = new MarketFeaturedSymbolService(repo);

        service.replaceAll(List.of(
                new FeaturedInput("BTCUSD", true),
                new FeaturedInput("ETHUSD", false),
                new FeaturedInput("XAUUSD", true)
        ));

        List<MarketFeaturedSymbol> stored = repo.findAllOrdered();
        Assertions.assertEquals(3, stored.size());
        Assertions.assertEquals("BTCUSD", stored.get(0).platformSymbol());
        Assertions.assertEquals(0, stored.get(0).sortOrder());
        Assertions.assertEquals(1, stored.get(0).enabled());
        Assertions.assertEquals("ETHUSD", stored.get(1).platformSymbol());
        Assertions.assertEquals(1, stored.get(1).sortOrder());
        Assertions.assertEquals(0, stored.get(1).enabled());
        Assertions.assertEquals(2, stored.get(2).sortOrder());
    }

    @Test
    void replaceAllTrimsAndDropsBlankSymbols() {
        StubRepository repo = new StubRepository();
        MarketFeaturedSymbolService service = new MarketFeaturedSymbolService(repo);

        service.replaceAll(List.of(
                new FeaturedInput("  BTCUSD  ", true),
                new FeaturedInput("   ", true)
        ));

        List<MarketFeaturedSymbol> stored = repo.findAllOrdered();
        Assertions.assertEquals(1, stored.size());
        Assertions.assertEquals("BTCUSD", stored.get(0).platformSymbol());
    }

    @Test
    void listEnabledSymbolsKeepsOrderAndFiltersDisabled() {
        StubRepository repo = new StubRepository();
        OffsetDateTime now = OffsetDateTime.now();
        repo.replaceAll(List.of(
                new MarketFeaturedSymbol("BTCUSD", 0, 1, now, now),
                new MarketFeaturedSymbol("ETHUSD", 1, 0, now, now),
                new MarketFeaturedSymbol("XAUUSD", 2, 1, now, now)
        ));
        MarketFeaturedSymbolService service = new MarketFeaturedSymbolService(repo);

        Assertions.assertEquals(List.of("BTCUSD", "XAUUSD"), service.listEnabledSymbols());
    }
}
