package com.falconx.wallet.dto;

import java.util.List;

/**
 * 幂等确保用户默认入金地址响应。
 *
 * @param addresses 入金地址列表
 */
public record EnsureWalletDepositAddressesResponse(
        List<WalletDepositAddressItemResponse> addresses
) {
}
