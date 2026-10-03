package com.falconx.trading.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STAGE-14B Task 3：FxRateConverterHelper 单元测试。
 *
 * <p>测试场景与 market-side {@code FxRateConverterTests} 保持一致，验证同一算法逻辑。
 */
class FxRateConverterHelperTests {

    @Test
    void same_currency_returns_one() {
        Optional<BigDecimal> rate = FxRateConverterHelper.compute(Map.of(), "USD", "USD");
        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void direct_pair_returns_rate() {
        Map<String, BigDecimal> rates = Map.of("EUR/USD", new BigDecimal("1.0800"));
        Optional<BigDecimal> rate = FxRateConverterHelper.compute(rates, "EUR", "USD");
        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo("1.0800");
    }

    @Test
    void reverse_pair_returns_reciprocal() {
        Map<String, BigDecimal> rates = Map.of("EUR/USD", new BigDecimal("1.0800"));
        // USD→EUR = 1 / 1.08 = 0.92592593
        Optional<BigDecimal> rate = FxRateConverterHelper.compute(rates, "USD", "EUR");
        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo(new BigDecimal("0.92592593"));
    }

    @Test
    void cross_pair_via_usd_pivot() {
        // EUR/USD = 1.08, AUD/USD = 0.65 → EUR/AUD = 1.08 / 0.65 = 1.66153846
        Map<String, BigDecimal> rates = Map.of(
                "EUR/USD", new BigDecimal("1.0800"),
                "AUD/USD", new BigDecimal("0.6500")
        );
        Optional<BigDecimal> rate = FxRateConverterHelper.compute(rates, "EUR", "AUD");
        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo(new BigDecimal("1.66153846"));
    }

    @Test
    void cross_pair_with_usd_quoted_base() {
        // USD/JPY = 150, USD/CAD = 1.36 → JPY/CAD = (1/150) * 1.36 = 0.00906667
        Map<String, BigDecimal> rates = Map.of(
                "USD/JPY", new BigDecimal("150.000"),
                "USD/CAD", new BigDecimal("1.3600")
        );
        Optional<BigDecimal> rate = FxRateConverterHelper.compute(rates, "JPY", "CAD");
        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo(new BigDecimal("0.00906667"));
    }

    @Test
    void unknown_pair_returns_empty() {
        Optional<BigDecimal> rate = FxRateConverterHelper.compute(Map.of(), "XXX", "YYY");
        assertThat(rate).isEmpty();
    }

    // ---- USDT↔USD 1:1 锚定（FX 源无 USDT 对，账户币 USDT 经 peg 接入）----
    // 这些用例不向 rate map 注入任何 USDT 对，验证纯靠 peg 成立——对齐真实 market 管线（只产法币 USD 交叉对）。

    @Test
    void usd_to_usdt_pegged_one() {
        Optional<BigDecimal> rate = FxRateConverterHelper.compute(Map.of(), "USD", "USDT");
        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void usdt_to_usd_pegged_one() {
        Optional<BigDecimal> rate = FxRateConverterHelper.compute(Map.of(), "USDT", "USD");
        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void quote_currency_to_usdt_via_peg() {
        // USD/CAD = 1.38 → CAD→USD = 1/1.38 = 0.72463768；USDT 锚定 USD → CAD→USDT 同值。
        // 这正是 demo AUDCAD(QC=CAD) 账户币 USDT 之前 FX_RATE_UNAVAILABLE 的修复路径。
        Map<String, BigDecimal> rates = Map.of("USD/CAD", new BigDecimal("1.3800"));
        Optional<BigDecimal> rate = FxRateConverterHelper.compute(rates, "CAD", "USDT");
        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo(new BigDecimal("0.72463768"));
    }

    @Test
    void usdt_to_quote_currency_via_peg() {
        // USDT→CAD：USDT 锚定 USD，USD→CAD = 1.38。
        Map<String, BigDecimal> rates = Map.of("USD/CAD", new BigDecimal("1.3800"));
        Optional<BigDecimal> rate = FxRateConverterHelper.compute(rates, "USDT", "CAD");
        assertThat(rate).isPresent();
        assertThat(rate.get()).isEqualByComparingTo(new BigDecimal("1.38000000"));
    }

    @Test
    void peg_only_helps_usdt_side_not_unknown_currency() {
        // 仅 USDT 侧锚定；另一侧（EUR）若无 USD 对仍无法解析 → empty（peg 不是万能兜底）。
        Optional<BigDecimal> rate = FxRateConverterHelper.compute(Map.of(), "EUR", "USDT");
        assertThat(rate).isEmpty();
    }
}
