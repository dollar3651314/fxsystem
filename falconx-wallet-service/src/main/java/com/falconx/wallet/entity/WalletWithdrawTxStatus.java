package com.falconx.wallet.entity;

/**
 * STAGE-7-WITHDRAW Phase 3：链上交易状态。
 *
 * <ul>
 *   <li>{@code SIGNING} 已构造 raw tx 并准备签名 / 广播；未真正提交链上</li>
 *   <li>{@code BROADCAST} 已通过 RPC 发出，等待确认</li>
 *   <li>{@code CONFIRMED} 链上确认达 min_confirmations</li>
 *   <li>{@code FAILED} 广播失败 / 链上 revert / 超时 / nonce 冲突</li>
 * </ul>
 */
public enum WalletWithdrawTxStatus {
    SIGNING(0),
    BROADCAST(1),
    CONFIRMED(2),
    FAILED(3);

    private final int code;

    WalletWithdrawTxStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static WalletWithdrawTxStatus fromCode(int code) {
        return switch (code) {
            case 0 -> SIGNING;
            case 1 -> BROADCAST;
            case 2 -> CONFIRMED;
            case 3 -> FAILED;
            default -> throw new IllegalArgumentException("Unknown withdraw tx status: " + code);
        };
    }
}
