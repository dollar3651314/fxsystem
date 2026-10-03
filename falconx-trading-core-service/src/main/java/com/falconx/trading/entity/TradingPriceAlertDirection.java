package com.falconx.trading.entity;

/**
 * STAGE-4-PRICE-ALERT：价格告警方向。
 */
public enum TradingPriceAlertDirection {
    ABOVE(1),
    BELOW(2);

    private final int code;

    TradingPriceAlertDirection(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static TradingPriceAlertDirection fromCode(int code) {
        return switch (code) {
            case 1 -> ABOVE;
            case 2 -> BELOW;
            default -> throw new IllegalArgumentException("Unknown alert direction: " + code);
        };
    }
}
