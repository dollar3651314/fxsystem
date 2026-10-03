package com.falconx.wallet.entity;

/**
 * 钱包原始入金交易状态。
 *
 * <p>状态集合与状态机规范保持一致：
 * DETECTED -> CONFIRMING -> CONFIRMED -> REVERSED，
 * 或在无效情况下进入 IGNORED。
 */
public enum WalletDepositStatus {
    DETECTED,
    CONFIRMING,
    CONFIRMED,
    REVERSED,
    IGNORED
}
