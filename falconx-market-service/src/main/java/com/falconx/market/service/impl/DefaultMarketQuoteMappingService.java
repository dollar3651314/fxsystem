package com.falconx.market.service.impl;

import com.falconx.market.entity.MarketSymbolQuoteMapping;
import com.falconx.market.provider.ExternalRawQuote;
import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.repository.MarketSymbolQuoteMappingRepository;
import com.falconx.market.service.MarketQuoteMappingService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 默认报价源映射服务。
 *
 * <p>高频 tick 处理不访问数据库，而是使用定时刷新的内存快照。
 * 这样既保持 owner 数据可配置，又避免每条报价都打到 MySQL。
 */
@Service
public class DefaultMarketQuoteMappingService implements MarketQuoteMappingService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMarketQuoteMappingService.class);
    private static final String DEFAULT_LP_CODE = "GODSA";

    private final MarketSymbolQuoteMappingRepository repository;
    private final MarketServiceProperties properties;
    private volatile Map<String, List<MarketSymbolQuoteMapping>> mappingsBySourceSymbol = Map.of();
    private volatile List<String> sourceSymbols = List.of();
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public DefaultMarketQuoteMappingService(MarketSymbolQuoteMappingRepository repository,
                                            MarketServiceProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public void refreshMappings() {
        String currentLpCode = currentLpCode();
        List<MarketSymbolQuoteMapping> mappings = repository.findAllLpSubscribedMappings().stream()
                .filter(mapping -> currentLpCode.equals(normalizeLpCode(mapping.sourceLpCode())))
                .toList();
        Map<String, List<MarketSymbolQuoteMapping>> nextBySource = mappings.stream()
                .collect(Collectors.groupingBy(
                        mapping -> sourceKey(mapping.sourceLpCode(), mapping.sourceSymbol()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        List<String> nextSourceSymbols = mappings.stream()
                .map(MarketSymbolQuoteMapping::sourceSymbol)
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .distinct()
                .sorted()
                .toList();
        mappingsBySourceSymbol = Map.copyOf(nextBySource);
        sourceSymbols = nextSourceSymbols;
        initialized.set(true);
        log.info("market.quote.mapping.refreshed lpCode={} mappingCount={} sourceSymbolCount={} sampleSourceSymbols={}",
                currentLpCode,
                mappings.size(),
                nextSourceSymbols.size(),
                nextSourceSymbols.stream().limit(10).toList());
    }

    @Override
    public List<String> sourceSymbols() {
        return sourceSymbols;
    }

    @Override
    public List<ExternalRawQuote> mapToPlatformQuotes(ExternalRawQuote sourceQuote) {
        if (sourceQuote == null || sourceQuote.ticker() == null || sourceQuote.ticker().isBlank()) {
            return List.of();
        }
        List<MarketSymbolQuoteMapping> mappings = mappingsBySourceSymbol.get(sourceKey(currentLpCode(), sourceQuote.ticker()));
        if (mappings == null || mappings.isEmpty()) {
            if (!initialized.get()) {
                return List.of(sourceQuote);
            }
            log.debug("market.quote.mapping.missing sourceSymbol={}", sourceQuote.ticker());
            return List.of();
        }
        return mappings.stream()
                .map(mapping -> mapQuote(sourceQuote, mapping))
                .toList();
    }

    private ExternalRawQuote mapQuote(ExternalRawQuote sourceQuote, MarketSymbolQuoteMapping mapping) {
        BigDecimal bid = sourceQuote.bid()
                .multiply(mapping.priceMultiplier())
                .add(mapping.bidAdjustment());
        BigDecimal ask = sourceQuote.ask()
                .multiply(mapping.priceMultiplier())
                .add(mapping.askAdjustment());
        return new ExternalRawQuote(
                mapping.platformSymbol(),
                bid,
                ask,
                sourceQuote.ts(),
                sourceQuote.source()
        );
    }

    private String normalizeKey(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String sourceKey(String lpCode, String symbol) {
        return normalizeLpCode(lpCode) + "|" + normalizeKey(symbol);
    }

    private String currentLpCode() {
        return normalizeLpCode(properties.getLp().getCode());
    }

    private String normalizeLpCode(String lpCode) {
        String normalized = lpCode == null ? "" : lpCode.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? DEFAULT_LP_CODE : normalized;
    }
}
