package com.falconx.trading.entity;

/**
 * trading-core 侧行情质量状态。
 */
public enum TradingQuoteQualityStatus {
    FRESH(false),
    STALE(true),
    NO_QUOTE(true),
    MARKET_CLOSED(true),
    ABNORMAL(true);

    private final boolean stale;

    TradingQuoteQualityStatus(boolean stale) {
        this.stale = stale;
    }

    public boolean stale() {
        return stale;
    }

    public boolean executable() {
        return this == FRESH;
    }

    public static TradingQuoteQualityStatus parse(String value, boolean timeStale) {
        if (value == null || value.isBlank()) {
            return timeStale ? STALE : FRESH;
        }
        try {
            return TradingQuoteQualityStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return STALE;
        }
    }
}
