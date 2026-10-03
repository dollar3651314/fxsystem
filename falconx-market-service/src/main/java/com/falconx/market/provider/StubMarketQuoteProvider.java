package com.falconx.market.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 测试和本地 stub profile 使用的通用行情 Provider。
 */
@Component
@Profile("stub")
public class StubMarketQuoteProvider implements MarketQuoteProvider {

    private static final Logger log = LoggerFactory.getLogger(StubMarketQuoteProvider.class);
    private static final BigDecimal MIN_SPREAD = new BigDecimal("0.00010000");

    @Override
    public void start(List<String> symbols, Consumer<ExternalRawQuote> quoteConsumer) {
        log.info("market.quote.provider.stub.started symbols={}", symbols == null ? 0 : symbols.size());
        if (symbols == null || symbols.isEmpty()) {
            log.warn("market.quote.provider.stub.skipped reason=no-enabled-symbols");
            return;
        }
        for (String symbol : symbols) {
            BigDecimal bid = stubBid(symbol);
            BigDecimal ask = bid.add(stubSpread(bid));
            quoteConsumer.accept(new ExternalRawQuote(
                    symbol,
                    bid,
                    ask,
                    OffsetDateTime.now(),
                    "STUB"
            ));
        }
    }

    @Override
    public void refreshSymbols(List<String> symbols) {
        log.info("market.quote.provider.stub.symbols.refreshed count={} symbols={}",
                symbols == null ? 0 : symbols.size(),
                symbols);
    }

    private BigDecimal stubBid(String symbol) {
        String normalized = normalizeSymbol(symbol);
        BigDecimal base = switch (normalized) {
            case "XAUUSD", "XAUUSDM" -> new BigDecimal("4723.00000000");
            case "XAGUSD" -> new BigDecimal("51.20000000");
            case "XPTUSD" -> new BigDecimal("1650.00000000");
            case "XPDUSD" -> new BigDecimal("1500.00000000");
            case "BTCUSD" -> new BigDecimal("101500.00000000");
            case "ETHUSD" -> new BigDecimal("3600.00000000");
            case "EURUSD" -> new BigDecimal("1.08500000");
            case "GBPUSD" -> new BigDecimal("1.27500000");
            case "USDJPY" -> new BigDecimal("154.00000000");
            case "AUDUSD" -> new BigDecimal("0.66500000");
            case "USDCAD" -> new BigDecimal("1.36500000");
            case "US30" -> new BigDecimal("41000.00000000");
            case "US500" -> new BigDecimal("5450.00000000");
            case "NAS100" -> new BigDecimal("19000.00000000");
            default -> fallbackBase(normalized);
        };
        return base.add(stubNudge(normalized)).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal fallbackBase(String symbol) {
        if (symbol.endsWith("JPY")) {
            return new BigDecimal("150.00000000");
        }
        if (symbol.endsWith("USD") || symbol.endsWith("EUR") || symbol.endsWith("GBP")) {
            return new BigDecimal("1.25000000");
        }
        int hash = Math.floorMod(symbol.hashCode(), 9_000);
        return BigDecimal.valueOf(10_000L + hash).movePointLeft(2);
    }

    private BigDecimal stubNudge(String symbol) {
        int hash = Math.floorMod(symbol.hashCode(), 200);
        return BigDecimal.valueOf(hash).movePointLeft(4);
    }

    private BigDecimal stubSpread(BigDecimal bid) {
        BigDecimal spread = bid.multiply(new BigDecimal("0.0001"));
        return spread.max(MIN_SPREAD).setScale(8, RoundingMode.HALF_UP);
    }

    private String normalizeSymbol(String symbol) {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        int suffixIndex = normalized.indexOf('.');
        if (suffixIndex > 0) {
            return normalized.substring(0, suffixIndex);
        }
        return normalized;
    }
}
