package com.falconx.trading.entity;

public enum TradingNotificationLevel {
    INFO(1),
    WARN(2),
    CRITICAL(3);

    private final int code;

    TradingNotificationLevel(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static TradingNotificationLevel fromCode(int code) {
        for (TradingNotificationLevel level : values()) {
            if (level.code == code) return level;
        }
        return INFO;
    }
}
