package com.falconx.market.service.impl;

import com.falconx.common.error.CommonErrorCode;
import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.KlineSnapshot;
import com.falconx.market.entity.MarketSymbolWithSpec;
import com.falconx.market.error.MarketBusinessException;
import com.falconx.market.repository.MarketKlineHistoryRepository;
import com.falconx.market.repository.MarketSymbolRepository;
import com.falconx.market.service.MarketKlineQueryService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * K 线历史查询服务默认实现。
 */
@Service
public class DefaultMarketKlineQueryService implements MarketKlineQueryService {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 500;

    private final MarketKlineHistoryRepository marketKlineHistoryRepository;
    private final MarketSymbolRepository marketSymbolRepository;
    private final MarketServiceProperties properties;

    public DefaultMarketKlineQueryService(MarketKlineHistoryRepository marketKlineHistoryRepository,
                                          MarketSymbolRepository marketSymbolRepository,
                                          MarketServiceProperties properties) {
        this.marketKlineHistoryRepository = marketKlineHistoryRepository;
        this.marketSymbolRepository = marketSymbolRepository;
        this.properties = properties;
    }

    @Override
    public List<KlineSnapshot> getRecentKlines(String symbol, String interval, Integer limit, String groupCode) {
        String canonicalSymbol = resolveCanonicalSymbol(symbol, groupCode)
                .orElseThrow(() -> new MarketBusinessException("30001", "Symbol Not Found"));

        String normalizedInterval = normalizeInterval(interval);
        int normalizedLimit = normalizeLimit(limit);
        return marketKlineHistoryRepository.findRecentKlines(
                canonicalSymbol,
                normalizedInterval,
                normalizedLimit
        );
    }

    private Optional<String> resolveCanonicalSymbol(String symbol, String groupCode) {
        String requestedSymbol = symbol == null ? "" : symbol.trim();
        if (requestedSymbol.isBlank()) {
            return Optional.empty();
        }
        return marketSymbolRepository.findVisibleTradingSymbol(requestedSymbol, groupCode)
                .map(MarketSymbolWithSpec::symbol);
    }

    private String normalizeInterval(String interval) {
        String normalized = interval == null || interval.isBlank()
                ? "1m"
                : interval.trim().toLowerCase(Locale.ROOT);
        if (!properties.getKline().getIntervals().contains(normalized)) {
            throw new MarketBusinessException(
                    CommonErrorCode.INVALID_REQUEST_PAYLOAD.code(),
                    CommonErrorCode.INVALID_REQUEST_PAYLOAD.message());
        }
        return normalized;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
