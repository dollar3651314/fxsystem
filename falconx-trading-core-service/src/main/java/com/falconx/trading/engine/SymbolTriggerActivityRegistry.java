package com.falconx.trading.engine;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 高频 tick 触发链路的活跃 symbol 索引。
 *
 * <p>该索引只决定是否需要进入挂单 / 价格提醒仓储扫描；实际触发仍以 MySQL
 * PENDING / ACTIVE 状态和 CAS 更新为准，避免把内存状态当作业务事实。
 */
@Component
public class SymbolTriggerActivityRegistry {

    private final Set<String> pendingOrderSymbols = ConcurrentHashMap.newKeySet();
    private final Set<String> priceAlertSymbols = ConcurrentHashMap.newKeySet();

    public void resetPendingOrderSymbols(Collection<String> symbols) {
        pendingOrderSymbols.clear();
        symbols.forEach(this::markPendingOrderActive);
    }

    public void resetPriceAlertSymbols(Collection<String> symbols) {
        priceAlertSymbols.clear();
        symbols.forEach(this::markPriceAlertActive);
    }

    public void markPendingOrderActive(String symbol) {
        add(pendingOrderSymbols, symbol);
    }

    public void markPendingOrderInactive(String symbol) {
        remove(pendingOrderSymbols, symbol);
    }

    public boolean mayHavePendingOrder(String symbol) {
        return contains(pendingOrderSymbols, symbol);
    }

    public void markPriceAlertActive(String symbol) {
        add(priceAlertSymbols, symbol);
    }

    public void markPriceAlertInactive(String symbol) {
        remove(priceAlertSymbols, symbol);
    }

    public boolean mayHavePriceAlert(String symbol) {
        return contains(priceAlertSymbols, symbol);
    }

    private static void add(Set<String> symbols, String symbol) {
        String normalized = normalize(symbol);
        if (normalized != null) {
            symbols.add(normalized);
        }
    }

    private static void remove(Set<String> symbols, String symbol) {
        String normalized = normalize(symbol);
        if (normalized != null) {
            symbols.remove(normalized);
        }
    }

    private static boolean contains(Set<String> symbols, String symbol) {
        String normalized = normalize(symbol);
        return normalized != null && symbols.contains(normalized);
    }

    private static String normalize(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
