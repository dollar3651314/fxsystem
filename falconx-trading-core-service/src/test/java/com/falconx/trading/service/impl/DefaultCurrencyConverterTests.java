package com.falconx.trading.service.impl;

import com.falconx.trading.service.FxRateService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * STAGE-14B Task 4：DefaultCurrencyConverter 单元测试。
 *
 * <p>覆盖 same-currency 短路 / direct / cross / 不可用返回 null / 精度 8 位 HALF_UP / null 入参。
 */
@ExtendWith(MockitoExtension.class)
class DefaultCurrencyConverterTests {

    private FxRateService fxRateService;
    private DefaultCurrencyConverter converter;

    @BeforeEach
    void setUp() {
        fxRateService = mock(FxRateService.class);
        converter = new DefaultCurrencyConverter(fxRateService);
    }

    @Test
    void same_currency_short_circuits_returns_amount_unchanged_without_query() {
        BigDecimal amount = new BigDecimal("123.456");
        BigDecimal result = converter.convert(amount, "USD", "USD");

        // 原样返回（不改变精度），且从不查询汇率
        assertThat(result).isSameAs(amount);
        verifyNoInteractions(fxRateService);
    }

    @Test
    void direct_rate_present_multiplies_and_scales_8_half_up() {
        // 100 EUR × 1.08 = 108.00000000
        when(fxRateService.queryRate("EUR", "USD")).thenReturn(Optional.of(new BigDecimal("1.08")));

        BigDecimal result = converter.convert(new BigDecimal("100"), "EUR", "USD");

        assertThat(result).isEqualByComparingTo("108.00000000");
        assertThat(result.scale()).isEqualTo(8);
    }

    @Test
    void cross_rate_stubbed_just_multiplies_and_scales() {
        // cross 逻辑在 FxRateService 内部，这里只验证乘法 + scale
        // EUR/AUD = 1.66153846，10 EUR → 16.61538460
        when(fxRateService.queryRate("EUR", "AUD")).thenReturn(Optional.of(new BigDecimal("1.66153846")));

        BigDecimal result = converter.convert(new BigDecimal("10"), "EUR", "AUD");

        assertThat(result).isEqualByComparingTo("16.61538460");
        assertThat(result.scale()).isEqualTo(8);
    }

    @Test
    void rate_unavailable_returns_null() {
        when(fxRateService.queryRate("XXX", "YYY")).thenReturn(Optional.empty());

        BigDecimal result = converter.convert(new BigDecimal("100"), "XXX", "YYY");

        assertThat(result).isNull();
        verify(fxRateService).queryRate("XXX", "YYY");
    }

    @Test
    void rounding_precision_many_decimals_scaled_to_8_half_up() {
        // 33.333333333 × 0.333333333 = 11.111111099999...; setScale(8, HALF_UP) → 11.11111110
        when(fxRateService.queryRate("AAA", "BBB")).thenReturn(Optional.of(new BigDecimal("0.333333333")));

        BigDecimal result = converter.convert(new BigDecimal("33.333333333"), "AAA", "BBB");

        assertThat(result.scale()).isEqualTo(8);
        assertThat(result).isEqualByComparingTo("11.11111110");
    }

    @Test
    void null_amount_throws_npe_without_query() {
        assertThatThrownBy(() -> converter.convert(null, "EUR", "USD"))
                .isInstanceOf(NullPointerException.class);
        verify(fxRateService, never()).queryRate(any(), any());
    }

    @Test
    void null_from_throws_npe() {
        assertThatThrownBy(() -> converter.convert(BigDecimal.ONE, null, "USD"))
                .isInstanceOf(NullPointerException.class);
        verify(fxRateService, never()).queryRate(any(), any());
    }

    @Test
    void null_to_throws_npe() {
        assertThatThrownBy(() -> converter.convert(BigDecimal.ONE, "EUR", null))
                .isInstanceOf(NullPointerException.class);
        verify(fxRateService, never()).queryRate(any(), any());
    }
}
