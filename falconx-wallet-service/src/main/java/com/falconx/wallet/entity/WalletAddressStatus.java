package com.falconx.wallet.entity;

/**
 * 钱包地址状态。
 *
 * <p>该状态只属于 wallet-service owner 范围，
 * 用于表达某个分配出去的钱包地址是否仍可继续被平台使用。
 */
public enum WalletAddressStatus {
    ASSIGNED,
    DISABLED
}
