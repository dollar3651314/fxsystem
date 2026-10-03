package com.falconx.trading.entity;

/**
 * STAGE-7-WITHDRAW：出金状态机（见 docs/domain/状态机规范.md §7A）。
 *
 * <p>迁移规则：
 * <pre>
 *   COOLING(0) -- 2h --&gt; PENDING(1)
 *   COOLING(0) -- user cancel --&gt; CANCELED(7)
 *   PENDING(1) -- admin approve small --&gt; APPROVED(2)
 *   PENDING(1) -- admin approve large --&gt; APPROVED_DELAYED(3)
 *   PENDING(1) -- admin reject --&gt; REJECTED(8)
 *   APPROVED_DELAYED(3) -- 6h --&gt; APPROVED(2)
 *   APPROVED_DELAYED(3) -- admin emergency-cancel --&gt; CANCELED(7)
 *   APPROVED(2) -- wallet broadcast --&gt; PROCESSING(4)
 *   PROCESSING(4) -- wallet confirmed --&gt; COMPLETED(5)
 *   PROCESSING(4)/APPROVED(2) -- wallet failed --&gt; FAILED(6)
 * </pre>
 */
public enum TradingWithdrawOrderStatus {
    COOLING(0),
    PENDING(1),
    APPROVED(2),
    APPROVED_DELAYED(3),
    PROCESSING(4),
    COMPLETED(5),
    FAILED(6),
    CANCELED(7),
    REJECTED(8);

    private final int code;

    TradingWithdrawOrderStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static TradingWithdrawOrderStatus fromCode(int code) {
        return switch (code) {
            case 0 -> COOLING;
            case 1 -> PENDING;
            case 2 -> APPROVED;
            case 3 -> APPROVED_DELAYED;
            case 4 -> PROCESSING;
            case 5 -> COMPLETED;
            case 6 -> FAILED;
            case 7 -> CANCELED;
            case 8 -> REJECTED;
            default -> throw new IllegalArgumentException("Unknown withdraw status: " + code);
        };
    }
}
