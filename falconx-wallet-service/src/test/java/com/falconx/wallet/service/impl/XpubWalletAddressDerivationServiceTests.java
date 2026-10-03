package com.falconx.wallet.service.impl;

import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.error.WalletBusinessException;
import com.falconx.wallet.error.WalletErrorCode;
import com.falconx.wallet.service.model.WalletDerivedAddress;
import com.falconx.wallet.support.WalletTestXpubSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * xpub 入金地址派生单元测试。
 */
class XpubWalletAddressDerivationServiceTests {

    @Test
    void shouldFailClosedWhenAccountXpubMissing() {
        XpubWalletAddressDerivationService service =
                new XpubWalletAddressDerivationService(new WalletServiceProperties());

        WalletBusinessException exception = Assertions.assertThrows(
                WalletBusinessException.class,
                () -> service.deriveAddress(ChainType.ETH, 1)
        );

        Assertions.assertEquals(WalletErrorCode.WALLET_ADDRESS_ALLOCATION_FAILED, exception.getErrorCode());
        Assertions.assertEquals("missing_account_xpub", exception.getContext().get("reason"));
    }

    @Test
    void shouldRejectUnsupportedChain() {
        WalletServiceProperties properties = new WalletServiceProperties();
        properties.getDerivation().setEthAccountXpub(WalletTestXpubSupport.ethAccountXpub());
        properties.getDerivation().setTronAccountXpub(WalletTestXpubSupport.tronAccountXpub());
        XpubWalletAddressDerivationService service = new XpubWalletAddressDerivationService(properties);

        WalletBusinessException exception = Assertions.assertThrows(
                WalletBusinessException.class,
                () -> service.deriveAddress(ChainType.BSC, 1)
        );

        Assertions.assertEquals(WalletErrorCode.UNSUPPORTED_CHAIN, exception.getErrorCode());
    }

    @Test
    void shouldDeriveExpectedEthAndTronAddresses() {
        WalletServiceProperties properties = new WalletServiceProperties();
        properties.getDerivation().setEthAccountXpub(WalletTestXpubSupport.ethAccountXpub());
        properties.getDerivation().setTronAccountXpub(WalletTestXpubSupport.tronAccountXpub());
        XpubWalletAddressDerivationService service = new XpubWalletAddressDerivationService(properties);

        WalletDerivedAddress eth = service.deriveAddress(ChainType.ETH, 1);
        WalletDerivedAddress tron = service.deriveAddress(ChainType.TRON, 1);

        Assertions.assertEquals("ERC20", eth.network());
        Assertions.assertEquals("m/44'/60'/0'/0/1", eth.derivationPath());
        Assertions.assertEquals(WalletTestXpubSupport.expectedEthAddress(1), eth.address());
        Assertions.assertEquals("TRC20", tron.network());
        Assertions.assertEquals("m/44'/195'/0'/0/1", tron.derivationPath());
        Assertions.assertEquals(WalletTestXpubSupport.expectedTronAddress(1), tron.address());
    }
}
