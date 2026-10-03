package com.falconx.wallet.command;

/**
 * 幂等确保用户默认入金地址命令。
 *
 * @param userId 用户 ID，由 gateway 认证后注入
 */
public record EnsureWalletDepositAddressesCommand(Long userId) {
}
