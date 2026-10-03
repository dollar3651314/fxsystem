package com.falconx.trading.entity;

public enum TradingNotificationStatus {
    UNREAD(0),
    READ(1);

    private final int code;

    TradingNotificationStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static TradingNotificationStatus fromCode(int code) {
        for (TradingNotificationStatus status : values()) {
            if (status.code == code) return status;
        }
        return UNREAD;
    }
}
