package com.falconx.trading.entity;

/**
 * STAGE-3-PENDING-ORDER：挂单状态。
 *
 * <p>编码与 DB 一致：1/2/3/4/5 与 t_pending_order_trigger.status。
 */
public enum TradingPendingOrderStatus {
    PENDING(1),
    TRIGGERED(2),
    CANCELLED(3),
    EXPIRED(4),
    REJECTED(5);

    private final int code;

    TradingPendingOrderStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static TradingPendingOrderStatus fromCode(int code) {
        return switch (code) {
            case 1 -> PENDING;
            case 2 -> TRIGGERED;
            case 3 -> CANCELLED;
            case 4 -> EXPIRED;
            case 5 -> REJECTED;
            default -> throw new IllegalArgumentException("Unknown pending order status: " + code);
        };
    }
}
