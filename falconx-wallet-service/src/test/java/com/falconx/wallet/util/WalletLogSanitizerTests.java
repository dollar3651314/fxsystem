package com.falconx.wallet.util;

import java.net.URI;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 钱包日志脱敏工具测试。
 */
class WalletLogSanitizerTests {

    @Test
    void shouldMaskSensitiveRpcUrlPathAndQuery() {
        URI rpcUrl = URI.create("wss://user:pass@eth-mainnet.g.alchemy.com/v2/secret-token?apikey=secret#fragment");

        String maskedUrl = WalletLogSanitizer.maskRpcUrl(rpcUrl);

        Assertions.assertEquals("wss://eth-mainnet.g.alchemy.com/v2/[REDACTED]", maskedUrl);
        Assertions.assertFalse(maskedUrl.contains("secret-token"));
        Assertions.assertFalse(maskedUrl.contains("apikey"));
        Assertions.assertFalse(maskedUrl.contains("user:pass"));
    }

    @Test
    void shouldKeepNonSensitiveLocalRpcUrlReadable() {
        URI rpcUrl = URI.create("http://localhost:8545");

        String maskedUrl = WalletLogSanitizer.maskRpcUrl(rpcUrl);

        Assertions.assertEquals("http://localhost:8545", maskedUrl);
    }
}
