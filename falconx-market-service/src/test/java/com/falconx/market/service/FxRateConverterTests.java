package com.falconx.market.service;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FxRateConverterTests {

    @Test
    void direct_pair_returns_rate() {
        FxRateConverter c = new FxRateConverter(Map.of("EUR/USD", new BigDecimal("1.0800")));
        assertThat(c.rate("EUR", "USD")).isEqualByComparingTo("1.0800");
    }

    @Test
    void same_currency_returns_one() {
        FxRateConverter c = new FxRateConverter(Map.of());
        assertThat(c.rate("USD", "USD")).isEqualByComparingTo("1");
    }

    @Test
    void reverse_pair_returns_reciprocal() {
        FxRateConverter c = new FxRateConverter(Map.of("EUR/USD", new BigDecimal("1.0800")));
        // USD→EUR = 1 / 1.0800
        assertThat(c.rate("USD", "EUR"))
            .isEqualByComparingTo(new BigDecimal("0.92592593"));  // 8 位精度
    }

    @Test
    void cross_pair_via_usd_pivot() {
        // EUR/USD = 1.08, AUD/USD = 0.65 → EUR/AUD = 1.08 / 0.65 = 1.66153846
        FxRateConverter c = new FxRateConverter(Map.of(
            "EUR/USD", new BigDecimal("1.0800"),
            "AUD/USD", new BigDecimal("0.6500")
        ));
        assertThat(c.rate("EUR", "AUD")).isEqualByComparingTo(new BigDecimal("1.66153846"));
    }

    @Test
    void cross_pair_with_usd_quoted_base() {
        // USD/JPY = 150, USD/CAD = 1.36 → JPY/CAD = (1/150) × 1.36 = 0.00906667
        FxRateConverter c = new FxRateConverter(Map.of(
            "USD/JPY", new BigDecimal("150.000"),
            "USD/CAD", new BigDecimal("1.3600")
        ));
        assertThat(c.rate("JPY", "CAD")).isEqualByComparingTo(new BigDecimal("0.00906667"));
    }

    // USDT↔USD 1:1 锚定（与 trading-core FxRateConverterHelper 保持一致；FX 源无 USDT 对）。
    @Test
    void usd_usdt_pegged_one() {
        FxRateConverter c = new FxRateConverter(Map.of());
        assertThat(c.rate("USD", "USDT")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(c.rate("USDT", "USD")).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void cross_to_usdt_via_peg() {
        // USD/CAD = 1.38 → CAD→USD = 0.72463768；USDT 锚定 USD → CAD→USDT 同值。
        FxRateConverter c = new FxRateConverter(Map.of("USD/CAD", new BigDecimal("1.3800")));
        assertThat(c.rate("CAD", "USDT")).isEqualByComparingTo(new BigDecimal("0.72463768"));
    }
}
