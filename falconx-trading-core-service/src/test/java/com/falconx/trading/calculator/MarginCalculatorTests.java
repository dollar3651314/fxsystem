package com.falconx.trading.calculator;

import com.falconx.trading.service.FxRateService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * STAGE-14B Task 6：MarginCalculator 多币种初始保证金计算单元测试。
 *
 * <p>对齐 master §3.2 公式（IM(QC)=Notional/lev、IM(AC)=IM(QC)×fx）与
 * §3.3 EURAUD 0.1 lot 200x USDT 账户完整算例。FX rate 经 {@link FxRateService#queryRate} mock。
 */
@ExtendWith(MockitoExtension.class)
class MarginCalculatorTests {

    @Mock
    private FxRateService fxRateService;

    private final MarginCalculator calculator = new MarginCalculator();

    /**
     * 用例 1：quote == account（同币种）→ fx=1，inQuote==inAccount，且不查询 FxRateService。
     *
     * <p>BTCUSDT、账户 USDT：notional = 40000 × 0.01 = 400，lev=10 → IM=40。
     */
    @Test
    void shouldReturnIdentityWhenQuoteEqualsAccount() {
        MarginResult result = calculator.calculateInitialMargin(
                new BigDecimal("40000"), new BigDecimal("0.01"), new BigDecimal("10"),
                "USDT", "USDT", fxRateService);

        Assertions.assertEquals(0, result.inQuote().compareTo(new BigDecimal("40")), "inQuote 应为 40");
        Assertions.assertEquals(0, result.inAccount().compareTo(new BigDecimal("40")), "同币种 inAccount==inQuote");
        Assertions.assertEquals(0, result.fxRate().compareTo(BigDecimal.ONE), "同币种 fxRate=1");
        // 同币种不应触发 FX 查询
        Mockito.verifyNoInteractions(fxRateService);
    }

    /**
     * 用例 2：master §3.3 EURAUD 0.1 lot(10000 EUR) 200x 算例。
     *
     * <p>fillPrice(AUD)=1.6500、notional=16500 AUD、IM(AUD)=82.5；fx(AUD→USDT)=0.6500 →
     * IM(USDT)=82.5 × 0.65 = 53.625。
     */
    @Test
    void shouldConvertEuraudExampleToAccountCurrency() {
        Mockito.when(fxRateService.queryRate("AUD", "USDT"))
                .thenReturn(Optional.of(new BigDecimal("0.6500")));

        MarginResult result = calculator.calculateInitialMargin(
                new BigDecimal("1.6500"), new BigDecimal("10000"), new BigDecimal("200"),
                "AUD", "USDT", fxRateService);

        Assertions.assertEquals(0, result.inQuote().compareTo(new BigDecimal("82.5")), "IM(AUD)=82.5");
        Assertions.assertEquals(0, result.inAccount().compareTo(new BigDecimal("53.625")), "IM(USDT)=53.625");
        Assertions.assertEquals(0, result.fxRate().compareTo(new BigDecimal("0.65")), "fx(AUD→USDT)=0.65");
    }

    /**
     * 用例 3：FX 不可用（queryRate 返回 empty）→ inAccount/fxRate 为 null，inQuote 照常给出。
     */
    @Test
    void shouldReturnNullAccountWhenFxUnavailable() {
        Mockito.when(fxRateService.queryRate("AUD", "USDT")).thenReturn(Optional.empty());

        MarginResult result = calculator.calculateInitialMargin(
                new BigDecimal("1.6500"), new BigDecimal("10000"), new BigDecimal("200"),
                "AUD", "USDT", fxRateService);

        Assertions.assertEquals(0, result.inQuote().compareTo(new BigDecimal("82.5")), "inQuote 仍应给出");
        Assertions.assertNull(result.inAccount(), "FX 不可用时 inAccount 为 null");
        Assertions.assertNull(result.fxRate(), "FX 不可用时 fxRate 为 null");
    }

    /**
     * 用例 4：inQuote 精度——8 位 DOWN 截断（非四舍五入）。
     *
     * <p>fillPrice=1.23456789、qty=1、lev=3 → 0.41152263（1.23456789/3=0.41152263，
     * 第 9 位被 DOWN 截断）。fx=1 同币种验证 inAccount 与 inQuote 一致精度。
     */
    @Test
    void shouldTruncateInQuoteToEightScaleDown() {
        MarginResult result = calculator.calculateInitialMargin(
                new BigDecimal("1.23456789"), BigDecimal.ONE, new BigDecimal("3"),
                "USDT", "USDT", fxRateService);

        Assertions.assertEquals(new BigDecimal("0.41152263"), result.inQuote(), "8 位 DOWN 截断");
    }

    /**
     * 用例 5：inAccount 精度——换算后 8 位 HALF_UP。
     *
     * <p>inQuote=82.5、fx=0.123456789 → 82.5 × 0.123456789 = 10.185185...，
     * setScale(8, HALF_UP) = 10.18518509（第 9 位 7 进 1）。
     */
    @Test
    void shouldRoundInAccountHalfUpToEightScale() {
        Mockito.when(fxRateService.queryRate("AUD", "USDT"))
                .thenReturn(Optional.of(new BigDecimal("0.123456789")));

        MarginResult result = calculator.calculateInitialMargin(
                new BigDecimal("1.6500"), new BigDecimal("10000"), new BigDecimal("200"),
                "AUD", "USDT", fxRateService);

        // 82.5 × 0.123456789 = 10.1851851... HALF_UP 8 位
        Assertions.assertEquals(new BigDecimal("10.18518509"), result.inAccount(), "inAccount 8 位 HALF_UP");
    }

    /**
     * 用例 6：交叉对——FxRateService 已封装 USD pivot 交叉逻辑，MarginCalculator 仅消费其 rate。
     *
     * <p>EURJPY 计价币 JPY、账户 USDT：fx(JPY→USDT)=0.0067（FxRateService 内部交叉算好）。
     * notional(JPY)=160.50 × 100 = 16050、lev=50 → IM(JPY)=321、IM(USDT)=321×0.0067=2.1507。
     */
    @Test
    void shouldConsumeCrossRateFromFxRateService() {
        Mockito.when(fxRateService.queryRate("JPY", "USDT"))
                .thenReturn(Optional.of(new BigDecimal("0.0067")));

        MarginResult result = calculator.calculateInitialMargin(
                new BigDecimal("160.50"), new BigDecimal("100"), new BigDecimal("50"),
                "JPY", "USDT", fxRateService);

        Assertions.assertEquals(0, result.inQuote().compareTo(new BigDecimal("321")), "IM(JPY)=321");
        Assertions.assertEquals(0, result.inAccount().compareTo(new BigDecimal("2.1507")), "IM(USDT)=2.1507");
        Assertions.assertEquals(0, result.fxRate().compareTo(new BigDecimal("0.0067")), "fxRate 透传");
    }

    // ─── STAGE-14B Task 8：货币感知手续费 calculateFee（复用 MarginResult 三元组）─────

    /**
     * Task 8 Fee 用例 1：quote == account（同币种）→ fx=1，inQuote==inAccount，且不查询 FxRateService。
     *
     * <p>BTCUSDT、账户 USDT：fillPrice=40000、qty=0.01、feeRate=0.0005 → Fee=400×0.0005=0.2。
     */
    @Test
    void shouldComputeFeeIdentityWhenQuoteEqualsAccount() {
        MarginResult fee = calculator.calculateFee(
                new BigDecimal("40000"), new BigDecimal("0.01"), new BigDecimal("0.0005"),
                "USDT", "USDT", fxRateService);

        Assertions.assertEquals(0, fee.inQuote().compareTo(new BigDecimal("0.2")), "Fee(QC)=0.2");
        Assertions.assertEquals(0, fee.inAccount().compareTo(new BigDecimal("0.2")), "同币种 Fee(AC)==Fee(QC)");
        Assertions.assertEquals(0, fee.fxRate().compareTo(BigDecimal.ONE), "同币种 fxRate=1");
        Mockito.verifyNoInteractions(fxRateService);
    }

    /**
     * Task 8 Fee 用例 2：异币种换算真值。EURAUD：fillPrice(AUD)=1.6500、qty=10000、feeRate=0.0005 →
     * Fee(AUD)=16500×0.0005=8.25；fx(AUD→USDT)=0.6500 → Fee(USDT)=8.25×0.65=5.3625。
     */
    @Test
    void shouldConvertFeeToAccountCurrency() {
        Mockito.when(fxRateService.queryRate("AUD", "USDT"))
                .thenReturn(Optional.of(new BigDecimal("0.6500")));

        MarginResult fee = calculator.calculateFee(
                new BigDecimal("1.6500"), new BigDecimal("10000"), new BigDecimal("0.0005"),
                "AUD", "USDT", fxRateService);

        Assertions.assertEquals(0, fee.inQuote().compareTo(new BigDecimal("8.25")), "Fee(AUD)=8.25");
        Assertions.assertEquals(0, fee.inAccount().compareTo(new BigDecimal("5.3625")), "Fee(USDT)=5.3625");
        Assertions.assertEquals(0, fee.fxRate().compareTo(new BigDecimal("0.65")), "fx(AUD→USDT)=0.65");
    }

    /**
     * Task 8 Fee 用例 3：FX 不可用（queryRate empty）→ inAccount/fxRate 为 null，inQuote 照常给出，不抛。
     */
    @Test
    void shouldReturnNullFeeAccountWhenFxUnavailable() {
        Mockito.when(fxRateService.queryRate("AUD", "USDT")).thenReturn(Optional.empty());

        MarginResult fee = calculator.calculateFee(
                new BigDecimal("1.6500"), new BigDecimal("10000"), new BigDecimal("0.0005"),
                "AUD", "USDT", fxRateService);

        Assertions.assertEquals(0, fee.inQuote().compareTo(new BigDecimal("8.25")), "Fee(QC) 仍应给出");
        Assertions.assertNull(fee.inAccount(), "FX 不可用时 Fee(AC) 为 null");
        Assertions.assertNull(fee.fxRate(), "FX 不可用时 fxRate 为 null");
    }

    /**
     * Task 8 Fee 用例 4：inQuote 精度——8 位 DOWN 截断（沿用原 3 参 calculateFee 口径）。
     *
     * <p>fillPrice=1.23456789、qty=1、feeRate=1 → 1.23456789，再 ×1 不变；
     * 用 feeRate 制造第 9 位：fillPrice=1、qty=1、feeRate=0.123456789 → 0.12345678（第 9 位 DOWN 截断）。
     */
    @Test
    void shouldTruncateFeeInQuoteToEightScaleDown() {
        MarginResult fee = calculator.calculateFee(
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.123456789"),
                "USDT", "USDT", fxRateService);

        Assertions.assertEquals(new BigDecimal("0.12345678"), fee.inQuote(), "Fee(QC) 8 位 DOWN 截断");
    }
}
