package com.falconx.trading.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 单测 {@link TradingCoreServiceProperties} 入金 token 白名单逻辑。
 *
 * <p>这套断言守住 deposit token 防护的边界：大小写无关、空白/null 容错、
 * 默认 USDT+USDC（Sepolia 测试期），不在白名单的（ETH/BNB/任意 ERC20）一律拒。
 */
class TradingCoreServicePropertiesTest {

    @Test
    void defaultsAllowsUsdtAndUsdc() {
        TradingCoreServiceProperties props = new TradingCoreServiceProperties();
        assertTrue(props.isDepositTokenAllowed("USDT"));
        assertTrue(props.isDepositTokenAllowed("USDC"));
    }

    @Test
    void caseInsensitive() {
        TradingCoreServiceProperties props = new TradingCoreServiceProperties();
        assertTrue(props.isDepositTokenAllowed("usdt"));
        assertTrue(props.isDepositTokenAllowed("UsDc"));
        assertTrue(props.isDepositTokenAllowed("  USDT "));
    }

    @Test
    void rejectsNativeAndUnknownTokens() {
        TradingCoreServiceProperties props = new TradingCoreServiceProperties();
        assertFalse(props.isDepositTokenAllowed("ETH"));
        assertFalse(props.isDepositTokenAllowed("BNB"));
        assertFalse(props.isDepositTokenAllowed("DAI"));
        assertFalse(props.isDepositTokenAllowed("RANDOM_SCAM_TOKEN"));
    }

    @Test
    void rejectsNullAndBlank() {
        TradingCoreServiceProperties props = new TradingCoreServiceProperties();
        assertFalse(props.isDepositTokenAllowed(null));
        assertFalse(props.isDepositTokenAllowed(""));
        assertFalse(props.isDepositTokenAllowed("   "));
    }

    @Test
    void setterNormalizesEntries() {
        TradingCoreServiceProperties props = new TradingCoreServiceProperties();
        props.setDepositTokenWhitelist(new LinkedHashSet<>(List.of("usdt", "  usdc ", "DAI")));
        assertTrue(props.isDepositTokenAllowed("USDT"));
        assertTrue(props.isDepositTokenAllowed("USDC"));
        assertTrue(props.isDepositTokenAllowed("DAI"));
        assertFalse(props.isDepositTokenAllowed("ETH"));
    }

    @Test
    void setterTolerantOfNullCollection() {
        TradingCoreServiceProperties props = new TradingCoreServiceProperties();
        props.setDepositTokenWhitelist(null);
        assertFalse(props.isDepositTokenAllowed("USDT"));
    }

    @Test
    void setterDropsNullAndBlankEntries() {
        TradingCoreServiceProperties props = new TradingCoreServiceProperties();
        LinkedHashSet<String> messy = new LinkedHashSet<>();
        messy.add("USDT");
        messy.add(null);
        messy.add("  ");
        messy.add("USDC");
        props.setDepositTokenWhitelist(messy);
        Set<String> whitelist = props.getDepositTokenWhitelist();
        assertTrue(whitelist.contains("USDT"));
        assertTrue(whitelist.contains("USDC"));
        assertFalse(whitelist.contains(""));
        assertFalse(whitelist.contains(null));
    }
}
