package com.falconx.trading.entity;

/**
 * STAGE-3-PENDING-ORDER：挂单类型。
 *
 * <p>编码与 DB 一致：2/3/4/5 与 t_pending_order_trigger.order_type。
 *
 * <p>SL_TP 是平仓挂单（parent_position_id 必有值），其他都是开仓挂单。
 */
public enum TradingPendingOrderType {
    LIMIT(2),
    STOP(3),
    STOP_LIMIT(4),
    SL_TP(5);

    private final int code;

    TradingPendingOrderType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static TradingPendingOrderType fromCode(int code) {
        return switch (code) {
            case 2 -> LIMIT;
            case 3 -> STOP;
            case 4 -> STOP_LIMIT;
            case 5 -> SL_TP;
            default -> throw new IllegalArgumentException("Unknown pending order type: " + code);
        };
    }

    public boolean isOpening() {
        return this != SL_TP;
    }
}
