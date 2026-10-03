package com.falconx.trading.entity;

/**
 * STAGE-7-WITHDRAW：出金网络枚举（与 wallet-service 入金链类型一致）。
 */
public enum TradingWithdrawNetwork {
    ERC20,
    TRC20;

    public static TradingWithdrawNetwork fromValue(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "ERC20" -> ERC20;
            case "TRC20" -> TRC20;
            default -> null;
        };
    }

    /**
     * STAGE-6-KYC trigger 2：出金网络 → wallet 侧 ChainType 字符串。
     * wallet {@code t_wallet_deposit_tx.chain} 存的是 {@code com.falconx.domain.enums.ChainType} 名（ETH/TRON）。
     */
    public String toWalletChain() {
        return switch (this) {
            case ERC20 -> "ETH";
            case TRC20 -> "TRON";
        };
    }
}
