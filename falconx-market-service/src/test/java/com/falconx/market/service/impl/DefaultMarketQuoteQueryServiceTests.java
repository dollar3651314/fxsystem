package com.falconx.market.service.impl;

import com.falconx.market.entity.MarketSymbolWithSpec;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.repository.MarketLatestQuoteRepository;
import com.falconx.market.repository.MarketQuoteHistoryRepository;
import com.falconx.market.repository.MarketSymbolRepository;
import com.falconx.market.service.MarketGroupMarkupService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 市场报价查询服务单元测试。
 */
class DefaultMarketQuoteQueryServiceTests {

    @Test
    void shouldReturnRecentQuoteTicksFromVisibleCanonicalSymbol() {
        MarketLatestQuoteRepository latestQuoteRepository = Mockito.mock(MarketLatestQuoteRepository.class);
        MarketQuoteHistoryRepository quoteHistoryRepository = Mockito.mock(MarketQuoteHistoryRepository.class);
        MarketSymbolRepository symbolRepository = Mockito.mock(MarketSymbolRepository.class);
        MarketGroupMarkupService groupMarkupService = Mockito.mock(MarketGroupMarkupService.class);
        Mockito.when(groupMarkupService.applyMarkup(Mockito.any(StandardQuote.class), Mockito.anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        DefaultMarketQuoteQueryService service = new DefaultMarketQuoteQueryService(
                latestQuoteRepository,
                quoteHistoryRepository,
                symbolRepository,
                groupMarkupService
        );

        Mockito.when(symbolRepository.findVisibleTradingSymbol("AUDCAD", "default"))
                .thenReturn(Optional.of(symbol("AUDCAD")));
        Mockito.when(quoteHistoryRepository.findRecentBySymbol("AUDCAD", 600))
                .thenReturn(List.of(
                        quote("AUDCAD", "0.99010", "0.99018", "2026-05-11T05:41:00Z"),
                        quote("AUDCAD", "0.99020", "0.99029", "2026-05-11T05:42:00Z")
                ));

        // STAGE-12: getRecentQuotes 返回 QuoteSnapshot(effective, base) 对
        var quotes = service.getRecentQuotes("AUDCAD", 600, "default");

        Assertions.assertEquals(2, quotes.size());
        Assertions.assertEquals(new BigDecimal("0.99010"), quotes.getFirst().effective().bid());
        Assertions.assertEquals(new BigDecimal("0.99018"), quotes.getFirst().effective().ask());
        // base === effective when no markup (零分配回退)
        Assertions.assertSame(quotes.getFirst().effective(), quotes.getFirst().base());
        Mockito.verify(quoteHistoryRepository).findRecentBySymbol("AUDCAD", 600);
    }

    @Test
    void shouldClampRecentQuoteLimit() {
        MarketLatestQuoteRepository latestQuoteRepository = Mockito.mock(MarketLatestQuoteRepository.class);
        MarketQuoteHistoryRepository quoteHistoryRepository = Mockito.mock(MarketQuoteHistoryRepository.class);
        MarketSymbolRepository symbolRepository = Mockito.mock(MarketSymbolRepository.class);
        MarketGroupMarkupService groupMarkupService = Mockito.mock(MarketGroupMarkupService.class);
        Mockito.when(groupMarkupService.applyMarkup(Mockito.any(StandardQuote.class), Mockito.anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        DefaultMarketQuoteQueryService service = new DefaultMarketQuoteQueryService(
                latestQuoteRepository,
                quoteHistoryRepository,
                symbolRepository,
                groupMarkupService
        );

        Mockito.when(symbolRepository.findVisibleTradingSymbol("AUDCAD", "default"))
                .thenReturn(Optional.of(symbol("AUDCAD")));
        Mockito.when(quoteHistoryRepository.findRecentBySymbol("AUDCAD", 1000))
                .thenReturn(List.of());

        service.getRecentQuotes("AUDCAD", 100_000, "default");

        Mockito.verify(quoteHistoryRepository).findRecentBySymbol("AUDCAD", 1000);
    }

    private MarketSymbolWithSpec symbol(String symbol) {
        return new MarketSymbolWithSpec(
                1L,
                symbol,
                1,
                "FX",
                "AUD",
                "CAD",
                5,
                2,
                BigDecimal.ONE,
                new BigDecimal("500"),
                BigDecimal.ONE,
                500,
                new BigDecimal("0.00030"),
                BigDecimal.ZERO,
                1
        );
    }

    private StandardQuote quote(String symbol, String bid, String ask, String ts) {
        BigDecimal bidPrice = new BigDecimal(bid);
        BigDecimal askPrice = new BigDecimal(ask);
        BigDecimal mid = bidPrice.add(askPrice).divide(new BigDecimal("2"));
        return new StandardQuote(
                symbol,
                bidPrice,
                askPrice,
                mid,
                mid,
                OffsetDateTime.parse(ts),
                "TM_QUOTE",
                false
        );
    }
}
