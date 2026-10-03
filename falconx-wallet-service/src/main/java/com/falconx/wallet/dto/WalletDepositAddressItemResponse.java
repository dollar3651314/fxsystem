package com.falconx.wallet.dto;

/**
 * 入金地址响应项。
 *
 * @param network 入金网络展示名
 * @param chain 链标识
 * @param token 代币符号
 * @param address 入金地址
 * @param addressIndex 地址派生索引
 * @param derivationPath HD 派生路径
 */
public record WalletDepositAddressItemResponse(
        String network,
        String chain,
        String token,
        String address,
        int addressIndex,
        String derivationPath
) {
}
