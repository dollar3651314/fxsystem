package com.falconx.wallet.service.model;

import com.falconx.domain.enums.ChainType;

/**
 * 由 account-level xpub 派生出的入金地址。
 */
public record WalletDerivedAddress(
        ChainType chain,
        String token,
        String network,
        String address,
        int addressIndex,
        String derivationPath
) {
}
