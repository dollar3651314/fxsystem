package com.falconx.market.service.impl;

import com.falconx.market.dto.MarketSymbolListItemResponse;
import com.falconx.market.dto.MarketSymbolsResponse;
import com.falconx.market.entity.MarketPriceStatus;
import com.falconx.market.entity.MarketSymbolWithSpec;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.repository.MarketLatestQuoteRepository;
import com.falconx.market.repository.MarketReferenceQuoteRepository;
import com.falconx.market.repository.MarketSymbolRepository;
import com.falconx.market.service.MarketGroupMarkupService;
import com.falconx.market.service.MarketSymbolQueryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 市场品种列表查询服务默认实现。
 *
 * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 后：listTradingSymbols 通过
 * {@link MarketSymbolRepository#findTradingSymbolsByGroupCode(String)} 拿到
 * {@link MarketSymbolWithSpec}（含 mapping 交易参数 JOIN），
 * 响应字段 maxLeverage/takerFeeRate/spread/min_qty/max_qty/min_notional 取自 mapping。
 */
@Service
public class DefaultMarketSymbolQueryService implements MarketSymbolQueryService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMarketSymbolQueryService.class);

    private final MarketSymbolRepository marketSymbolRepository;
    private final MarketLatestQuoteRepository marketLatestQuoteRepository;
    private final MarketReferenceQuoteRepository marketReferenceQuoteRepository;
    private final MarketGroupMarkupService marketGroupMarkupService;

    public DefaultMarketSymbolQueryService(MarketSymbolRepository marketSymbolRepository,
                                           MarketLatestQuoteRepository marketLatestQuoteRepository,
                                           MarketReferenceQuoteRepository marketReferenceQuoteRepository,
                                           MarketGroupMarkupService marketGroupMarkupService) {
        this.marketSymbolRepository = marketSymbolRepository;
        this.marketLatestQuoteRepository = marketLatestQuoteRepository;
        this.marketReferenceQuoteRepository = marketReferenceQuoteRepository;
        this.marketGroupMarkupService = marketGroupMarkupService;
    }

    @Override
    public MarketSymbolsResponse listTradingSymbols(String groupCode) {
        String normalizedGroupCode = normalizeGroupCode(groupCode);
        long t0 = System.currentTimeMillis();
        List<MarketSymbolWithSpec> symbols = marketSymbolRepository.findTradingSymbolsByGroupCode(normalizedGroupCode);
        long t1 = System.currentTimeMillis();

        // 性能优化（2026-05-12）：原实现对每个 symbol 串行调 latest + reference Redis，
        // 1571 symbols × HGETALL × 5-7ms ≈ 8-12s。改为 Redis pipeline 批量：
        //   1) latest.findBySymbols(N) 一次 RTT 拿全部命中（fresh 的走 LIVE，stale 的走 REFERENCE）
        //   2) reference.findBySymbols(missingLatest) 兜底 fast path（不走 ClickHouse 回填，避免 N 次串查）
        // 1462 个 MISSING 走 null quote 路径，体感不影响。
        List<String> symbolNames = symbols.stream().map(MarketSymbolWithSpec::symbol).toList();
        Map<String, StandardQuote> latestQuotes = marketLatestQuoteRepository.findBySymbols(symbolNames);

        List<String> missingLatest = new ArrayList<>();
        for (String name : symbolNames) {
            if (!latestQuotes.containsKey(name)) missingLatest.add(name);
        }
        Map<String, StandardQuote> referenceQuotes = missingLatest.isEmpty()
                ? Map.of()
                : marketReferenceQuoteRepository.findBySymbols(missingLatest);
        long t2 = System.currentTimeMillis();

        List<MarketSymbolListItemResponse> items = symbols.stream()
                .map(symbol -> toResponse(symbol, latestQuotes, referenceQuotes, normalizedGroupCode))
                .toList();
        log.info("market.symbols.list.completed groupCode={} count={} dbMs={} quoteMs={} totalMs={}",
                normalizedGroupCode, items.size(), t1 - t0, t2 - t1, System.currentTimeMillis() - t0);
        return new MarketSymbolsResponse(items);
    }

    private MarketSymbolListItemResponse toResponse(MarketSymbolWithSpec symbol,
                                                    Map<String, StandardQuote> latestQuotes,
                                                    Map<String, StandardQuote> referenceQuotes,
                                                    String groupCode) {
        StandardQuote latest = latestQuotes.get(symbol.symbol());
        if (latest != null && !latest.stale()) {
            return toResponse(symbol, marketGroupMarkupService.applyMarkup(latest, groupCode),
                    MarketPriceStatus.LIVE, true);
        }
        // latest 存在但 stale → 当 REFERENCE；不存在 → 用 reference map 兜底
        StandardQuote referenceQuote = latest != null ? latest : referenceQuotes.get(symbol.symbol());
        if (referenceQuote != null) {
            return toResponse(symbol, marketGroupMarkupService.applyMarkup(referenceQuote, groupCode),
                    MarketPriceStatus.REFERENCE, false);
        }
        return toResponse(symbol, null, MarketPriceStatus.MISSING, false);
    }

    private MarketSymbolListItemResponse toResponse(MarketSymbolWithSpec symbol,
                                                    StandardQuote quote,
                                                    MarketPriceStatus priceStatus,
                                                    boolean tradable) {
        return new MarketSymbolListItemResponse(
                symbol.symbol(),
                symbol.category(),
                symbol.marketCode(),
                symbol.baseCurrency(),
                symbol.quoteCurrency(),
                symbol.pricePrecision(),
                symbol.qtyPrecision(),
                symbol.minQty(),
                symbol.maxQty(),
                symbol.minNotional(),
                symbol.maxLeverage(),
                symbol.takerFeeRate(),
                symbol.spread(),
                quote == null ? null : quote.bid(),
                quote == null ? null : quote.ask(),
                quote == null ? null : quote.mid(),
                quote == null ? null : quote.mark(),
                quote == null ? null : quote.ts(),
                quote == null ? null : quote.source(),
                priceStatus,
                tradable
        );
    }

    private String normalizeGroupCode(String groupCode) {
        return groupCode == null || groupCode.isBlank() ? "default" : groupCode.trim();
    }
}
