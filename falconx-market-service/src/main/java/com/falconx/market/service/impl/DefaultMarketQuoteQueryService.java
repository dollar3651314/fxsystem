package com.falconx.market.service.impl;

import com.falconx.market.entity.StandardQuote;
import com.falconx.market.service.MarketQuoteQueryService.QuoteSnapshot;
import com.falconx.market.error.MarketBusinessException;
import com.falconx.market.repository.MarketLatestQuoteRepository;
import com.falconx.market.repository.MarketQuoteHistoryRepository;
import com.falconx.market.repository.MarketSymbolRepository;
import com.falconx.market.service.MarketGroupMarkupService;
import com.falconx.market.service.MarketQuoteQueryService;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 市场最新报价查询服务默认实现。
 *
 * <p>当前阶段该实现直接读取内存最新报价仓储，
 * 并把“品种无可用报价”转换为市场域业务异常。
 */
@Service
public class DefaultMarketQuoteQueryService implements MarketQuoteQueryService {

    private static final int DEFAULT_HISTORY_LIMIT = 600;
    private static final int MAX_HISTORY_LIMIT = 1000;

    private final MarketLatestQuoteRepository marketLatestQuoteRepository;
    private final MarketQuoteHistoryRepository marketQuoteHistoryRepository;
    private final MarketSymbolRepository marketSymbolRepository;
    private final MarketGroupMarkupService marketGroupMarkupService;

    public DefaultMarketQuoteQueryService(MarketLatestQuoteRepository marketLatestQuoteRepository,
                                          MarketQuoteHistoryRepository marketQuoteHistoryRepository,
                                          MarketSymbolRepository marketSymbolRepository,
                                          MarketGroupMarkupService marketGroupMarkupService) {
        this.marketLatestQuoteRepository = marketLatestQuoteRepository;
        this.marketQuoteHistoryRepository = marketQuoteHistoryRepository;
        this.marketSymbolRepository = marketSymbolRepository;
        this.marketGroupMarkupService = marketGroupMarkupService;
    }

    @Override
    public QuoteSnapshot getLatestQuote(String symbol, String groupCode) {
        String canonicalSymbol = marketSymbolRepository.findVisibleTradingSymbol(symbol, groupCode)
                .map(com.falconx.market.entity.MarketSymbolWithSpec::symbol)
                .orElseThrow(() -> new MarketBusinessException("30001", "Symbol Not Found"));
        StandardQuote base = marketLatestQuoteRepository.findBySymbol(canonicalSymbol)
                .orElseThrow(() -> new MarketBusinessException("30003", "Quote Not Available"));
        StandardQuote effective = marketGroupMarkupService.applyMarkup(base, groupCode);
        return new QuoteSnapshot(effective, base);
    }

    @Override
    public List<QuoteSnapshot> getRecentQuotes(String symbol, Integer limit, String groupCode) {
        String canonicalSymbol = marketSymbolRepository.findVisibleTradingSymbol(symbol, groupCode)
                .map(com.falconx.market.entity.MarketSymbolWithSpec::symbol)
                .orElseThrow(() -> new MarketBusinessException("30001", "Symbol Not Found"));
        List<StandardQuote> base = marketQuoteHistoryRepository.findRecentBySymbol(
                canonicalSymbol, normalizeHistoryLimit(limit));
        return base.stream()
                .map(q -> new QuoteSnapshot(marketGroupMarkupService.applyMarkup(q, groupCode), q))
                .toList();
    }

    private int normalizeHistoryLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_HISTORY_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));
    }
}
