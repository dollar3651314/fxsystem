package com.falconx.trading.calculator;

import java.math.BigDecimal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RiskExposureCalculatorTests {

    private final RiskExposureCalculator calculator = new RiskExposureCalculator();

    private BigDecimal usd(String longQty, String shortQty, String markPrice, BigDecimal fx) {
        return calculator.calculateNetExposureUsd(
                calculator.calculateNetExposure(new BigDecimal(longQty), new BigDecimal(shortQty)),
                new BigDecimal(markPrice),
                fx
        );
    }

    @Test
    void shouldCalculateNetExposureUsdFromLongShortAggregation() {
        // USD 计价（fx=1）：(3-1) × 9995 = 19990
        Assertions.assertEquals(new BigDecimal("19990.00000000"),
                usd("3.00000000", "1.00000000", "9995.00000000", BigDecimal.ONE));
    }

    @Test
    void shouldCalculateNegativeNetExposureUsdWhenShortSideDominates() {
        Assertions.assertEquals(new BigDecimal("-1250.00000000"),
                usd("1.50000000", "2.00000000", "2500.00000000", BigDecimal.ONE));
    }

    @Test
    void shouldReturnZeroNetExposureUsdWhenLongAndShortOffsetEachOther() {
        Assertions.assertEquals(new BigDecimal("0E-8"),
                usd("2.00000000", "2.00000000", "8888.00000000", BigDecimal.ONE));
    }

    // ===== 多币种 USD 化（2026-06-03）：×fx(QC→USD) =====

    @Test
    void 非USD计价按fx换算_JPY系不再虚高160倍() {
        // AUDJPY：净敞口 100 × markPrice 114.6(JPY) × fx(JPY→USD)=0.00625 → 71.625 USD
        Assertions.assertEquals(new BigDecimal("71.62500000"),
                usd("100.00000000", "0", "114.60000000", new BigDecimal("0.00625000")));
        // 旧口径（×1）会得 11460——160 倍虚高
        Assertions.assertEquals(new BigDecimal("11460.00000000"),
                usd("100.00000000", "0", "114.60000000", BigDecimal.ONE));
    }

    @Test
    void fx为null降级等价于1_与历史口径一致() {
        Assertions.assertEquals(new BigDecimal("19990.00000000"),
                usd("3.00000000", "1.00000000", "9995.00000000", null));
        // 两参历史重载 = fx=1
        Assertions.assertEquals(new BigDecimal("19990.00000000"),
                calculator.calculateNetExposureUsd(new BigDecimal("2.00000000"), new BigDecimal("9995.00000000")));
    }
}
