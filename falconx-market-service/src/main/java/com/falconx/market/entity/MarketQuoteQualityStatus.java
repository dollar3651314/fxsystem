package com.falconx.market.entity;

/**
 * 行情质量状态。
 *
 * <p>`FRESH` 是唯一可成交状态；其他状态只能用于快照同步、审计或展示参考，
 * 不允许进入成交、TP/SL 或强平触发。
 */
public enum MarketQuoteQualityStatus {
    FRESH(false),
    STALE(true),
    NO_QUOTE(true),
    MARKET_CLOSED(true),
    ABNORMAL(true);

    private final boolean stale;

    MarketQuoteQualityStatus(boolean stale) {
        this.stale = stale;
    }

    public boolean stale() {
        return stale;
    }

    public boolean executable() {
        return this == FRESH;
    }
}
