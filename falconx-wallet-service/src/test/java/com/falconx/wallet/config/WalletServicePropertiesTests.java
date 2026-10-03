package com.falconx.wallet.config;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@link WalletServiceProperties} 配置模型测试。
 */
class WalletServicePropertiesTests {

    @Test
    void shouldExposeConfiguredStablecoinContractsCaseInsensitively() {
        WalletServiceProperties.Chain chain = new WalletServiceProperties.Chain(
                URI.create("https://rpc.example.org"),
                Duration.ofMinutes(5),
                12,
                "block",
                "0"
        );
        chain.setUsdtContract("0xF31429D9133f91221aa4Dca7Cdfc626512BAdeBb");
        chain.setUsdtDecimals(6);
        chain.setUsdcContract("0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238");
        chain.setUsdcDecimals(6);

        Assertions.assertEquals(2, chain.tokenContracts().size());
        WalletServiceProperties.TokenContract usdt = chain.findTokenContract(
                "0xf31429d9133f91221aa4dca7cdfc626512badebb"
        ).orElseThrow();
        Assertions.assertEquals("USDT", usdt.symbol());
        Assertions.assertEquals(6, usdt.decimals());
    }
}
