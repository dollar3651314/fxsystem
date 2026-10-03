package com.falconx.trading.entity;

/**
 * STAGE-4-PRICE-ALERT：价格告警状态。
 *
 * <ul>
 *   <li>{@code ACTIVE} 等待触发；可被触发 3 次（每次间隔 ≥ 5 分钟）</li>
 *   <li>{@code EXHAUSTED} 已触发 3 次自动终结</li>
 *   <li>{@code CANCELLED} 用户主动撤销</li>
 *   <li>{@code ADMIN_DELETED} 管理员强制删除</li>
 * </ul>
 */
public enum TradingPriceAlertStatus {
    ACTIVE(1),
    EXHAUSTED(2),
    CANCELLED(3),
    ADMIN_DELETED(4);

    private final int code;

    TradingPriceAlertStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static TradingPriceAlertStatus fromCode(int code) {
        return switch (code) {
            case 1 -> ACTIVE;
            case 2 -> EXHAUSTED;
            case 3 -> CANCELLED;
            case 4 -> ADMIN_DELETED;
            default -> throw new IllegalArgumentException("Unknown alert status: " + code);
        };
    }
}
