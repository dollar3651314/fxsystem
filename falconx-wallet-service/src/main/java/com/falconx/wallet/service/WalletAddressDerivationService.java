package com.falconx.wallet.service;

import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.service.model.WalletDerivedAddress;

/**
 * 钱包入金地址派生服务。
 */
public interface WalletAddressDerivationService {

    /**
     * 按链和地址索引派生入金地址。
     *
     * @param chain 链类型
     * @param addressIndex 地址索引
     * @return 派生地址
     */
    WalletDerivedAddress deriveAddress(ChainType chain, int addressIndex);
}
