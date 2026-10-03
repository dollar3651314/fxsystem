package com.falconx.wallet.entity;

/**
 * STAGE-7-WITHDRAW：出金白名单状态。
 *
 * <ul>
 *   <li>{@code PENDING} 添加后 24h 冷静期内（不可用于出金）</li>
 *   <li>{@code ACTIVE} 冷静期通过，可用于出金</li>
 *   <li>{@code REMOVED} 用户已删除（保留历史；同地址可再次添加）</li>
 * </ul>
 */
public enum WalletWithdrawWhitelistStatus {
    PENDING(0),
    ACTIVE(1),
    REMOVED(2);

    private final int code;

    WalletWithdrawWhitelistStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static WalletWithdrawWhitelistStatus fromCode(int code) {
        return switch (code) {
            case 0 -> PENDING;
            case 1 -> ACTIVE;
            case 2 -> REMOVED;
            default -> throw new IllegalArgumentException("Unknown whitelist status: " + code);
        };
    }
}
