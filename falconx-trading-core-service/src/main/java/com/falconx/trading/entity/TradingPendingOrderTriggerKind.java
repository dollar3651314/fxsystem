package com.falconx.trading.entity;

/**
 * STAGE-3-PENDING-ORDER：SL_TP 细分类型。
 *
 * <p>仅当 {@link TradingPendingOrderType} 为 {@code SL_TP} 时有值。
 */
public enum TradingPendingOrderTriggerKind {
    TAKE_PROFIT(1),
    STOP_LOSS(2);

    private final int code;

    TradingPendingOrderTriggerKind(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static TradingPendingOrderTriggerKind fromCode(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 1 -> TAKE_PROFIT;
            case 2 -> STOP_LOSS;
            default -> throw new IllegalArgumentException("Unknown trigger kind: " + code);
        };
    }
}
