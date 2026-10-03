package com.falconx.trading.support;

import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.service.FxRateService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * `TC-TRD-040` 浮盈浮亏计算口径单元测试。
 */
class TradingPricingSupportTests {

    @ParameterizedTest
    @MethodSource("positionPnlCases")
    void shouldCalculatePositionPnlWithDirectionalEffectiveMarkPrice(TradingOrderSide side,
                                                                     String entryPrice,
                                                                     String effectiveMarkPrice,
                                                                     String quantity,
                                                                     String expectedPnl) {
        TradingPosition position = new TradingPosition(
                1L,
                1L,
                1L,
                "BTCUSDT",
                side,
                new BigDecimal(quantity),
                new BigDecimal(entryPrice),
                BigDecimal.ONE,
                new BigDecimal("0.005000"),
                1,
                new BigDecimal("10.00000000"),
                new BigDecimal("1000.00000000"),
                TradingMarginMode.ISOLATED,
                new BigDecimal("9000.00000000"),
                new BigDecimal("11000.00000000"),
                new BigDecimal("8000.00000000"),
                null,
                null,
                null,
                TradingPositionStatus.OPEN,
                BigDecimal.ZERO,
                "default",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                OffsetDateTime.now(),
                null,
                OffsetDateTime.now()
        );

        BigDecimal actual = TradingPricingSupport.calculatePositionPnl(
                position,
                new BigDecimal(effectiveMarkPrice)
        );

        Assertions.assertEquals(new BigDecimal(expectedPnl), actual);
    }

    private static Stream<Arguments> positionPnlCases() {
        return Stream.of(
                Arguments.of(TradingOrderSide.BUY, "10000.00000000", "10500.00000000", "1.00000000", "500.00000000"),
                Arguments.of(TradingOrderSide.BUY, "10000.00000000", "9500.00000000", "1.00000000", "-500.00000000"),
                Arguments.of(TradingOrderSide.SELL, "10000.00000000", "9500.00000000", "1.00000000", "500.00000000"),
                Arguments.of(TradingOrderSide.SELL, "10000.00000000", "10500.00000000", "1.00000000", "-500.00000000")
        );
    }

    /**
     * STAGE-12-GROUP-MARKUP TC-GM-UT-022 ~ TC-GM-UT-024：
     * resolvePositionMarkPrice(quote, position) overload 应用 position 冻结 markup。
     */
    @Test
    void TC_GM_UT_022_resolvePositionMarkPrice_buy_position_uses_bid_plus_bid_extra() {
        TradingPosition position = buildPosition(TradingOrderSide.BUY,
                "10000.00000000",
                new BigDecimal("0.50000000"),
                new BigDecimal("1.00000000"));
        TradingQuoteSnapshot quote = new TradingQuoteSnapshot(
                "BTCUSDT",
                new BigDecimal("9999.50000000"),
                new BigDecimal("10000.50000000"),
                new BigDecimal("10000.00000000"),
                OffsetDateTime.now(), "LP", false);

        BigDecimal mark = TradingPricingSupport.resolvePositionMarkPrice(quote, position);

        Assertions.assertEquals(0, mark.compareTo(new BigDecimal("10000.00000000")),
                "BUY 持仓平仓走 bid + bidExtraAtOpen = 9999.5 + 0.5 = 10000.0");
    }

    @Test
    void TC_GM_UT_023_resolvePositionMarkPrice_sell_position_uses_ask_plus_ask_extra() {
        TradingPosition position = buildPosition(TradingOrderSide.SELL,
                "10000.00000000",
                new BigDecimal("0.50000000"),
                new BigDecimal("1.00000000"));
        TradingQuoteSnapshot quote = new TradingQuoteSnapshot(
                "BTCUSDT",
                new BigDecimal("9999.50000000"),
                new BigDecimal("10000.50000000"),
                new BigDecimal("10000.00000000"),
                OffsetDateTime.now(), "LP", false);

        BigDecimal mark = TradingPricingSupport.resolvePositionMarkPrice(quote, position);

        Assertions.assertEquals(0, mark.compareTo(new BigDecimal("10001.50000000")),
                "SELL 持仓平仓走 ask + askExtraAtOpen = 10000.5 + 1.0 = 10001.5");
    }

    @Test
    void TC_GM_UT_024_calculatePositionPnl_buy_with_frozen_bid_extra_adds_to_effective_price() {
        TradingPosition position = buildPosition(TradingOrderSide.BUY,
                "10000.00000000",
                new BigDecimal("0.50000000"),
                BigDecimal.ZERO);
        // Caller 传入基准 bid 价
        BigDecimal baseMark = new BigDecimal("10500.00000000");

        BigDecimal pnl = TradingPricingSupport.calculatePositionPnl(position, baseMark);

        Assertions.assertEquals(0, pnl.compareTo(new BigDecimal("500.50000000")),
                "BUY: PnL = (markBase + bidExtra - entry) × qty = (10500 + 0.5 - 10000) × 1 = 500.5");
    }

    @Test
    void TC_GM_UT_024b_calculatePositionPnl_zero_markup_unchanged() {
        TradingPosition position = buildPosition(TradingOrderSide.BUY,
                "10000.00000000",
                BigDecimal.ZERO,
                BigDecimal.ZERO);
        BigDecimal pnl = TradingPricingSupport.calculatePositionPnl(position, new BigDecimal("10500.00000000"));

        Assertions.assertEquals(0, pnl.compareTo(new BigDecimal("500.00000000")),
                "0 markup 下 PnL 应与无 markup 时一致");
    }

    // ---------------------------------------------------------------------
    // STAGE-14B Task 7：货币感知 PnL（PnlResult + calculatePositionPnlInAccount）
    // ---------------------------------------------------------------------

    /**
     * Task 7 UT-1：同币种（quote==account）→ inQuote==inAccount，fxRate=1，且不查询 FX。
     */
    @Test
    void TC_14B_T7_001_sameCurrency_shortCircuit_noFxQuery() {
        FxRateService fxRateService = Mockito.mock(FxRateService.class);
        TradingPosition position = buildPosition(TradingOrderSide.BUY,
                "10000.00000000", BigDecimal.ZERO, BigDecimal.ZERO);

        PnlResult result = TradingPricingSupport.calculatePositionPnlInAccount(
                position, new BigDecimal("10500.00000000"), "USDT", "USDT", fxRateService);

        Assertions.assertEquals(0, result.inQuote().compareTo(new BigDecimal("500.00000000")), "inQuote");
        Assertions.assertEquals(0, result.inAccount().compareTo(new BigDecimal("500.00000000")), "inAccount==inQuote");
        Assertions.assertEquals(0, result.fxRate().compareTo(BigDecimal.ONE), "fxRate=1");
        Assertions.assertEquals("USDT", result.quoteCurrency());
        Mockito.verifyNoInteractions(fxRateService);
    }

    /**
     * Task 7 UT-2：异币种正常换算（master §3.3 风格：原币 PnL(AUD) × fx(AUD→USDT)）。
     */
    @Test
    void TC_14B_T7_002_crossCurrency_convertsInQuoteByFxRate() {
        FxRateService fxRateService = Mockito.mock(FxRateService.class);
        Mockito.when(fxRateService.queryRate("AUD", "USDT"))
                .thenReturn(Optional.of(new BigDecimal("0.65000000")));
        // BUY entry=1.6500, mark=1.7000, qty=10000 → inQuote = (1.7-1.65)*10000 = 500 AUD
        TradingPosition position = buildPosition(TradingOrderSide.BUY,
                "1.65000000", BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("10000.00000000"));

        PnlResult result = TradingPricingSupport.calculatePositionPnlInAccount(
                position, new BigDecimal("1.70000000"), "AUD", "USDT", fxRateService);

        Assertions.assertEquals(0, result.inQuote().compareTo(new BigDecimal("500.00000000")), "inQuote=500 AUD");
        // 500 × 0.65 = 325 USDT
        Assertions.assertEquals(0, result.inAccount().compareTo(new BigDecimal("325.00000000")), "inAccount=325 USDT");
        Assertions.assertEquals(0, result.fxRate().compareTo(new BigDecimal("0.65000000")), "fxRate=0.65");
        Assertions.assertEquals("AUD", result.quoteCurrency());
    }

    /**
     * Task 7 UT-3：负 PnL（亏损）换算 → inAccount 为负且 8 位 HALF_UP 正确。
     */
    @Test
    void TC_14B_T7_003_negativePnl_convertsWithCorrectHalfUpRounding() {
        FxRateService fxRateService = Mockito.mock(FxRateService.class);
        Mockito.when(fxRateService.queryRate("AUD", "USDT"))
                .thenReturn(Optional.of(new BigDecimal("0.63000000")));
        // SELL entry=1.6500, mark=1.6600, qty=10000 → inQuote = (1.65-1.66)*10000 = -100 AUD
        TradingPosition position = buildPosition(TradingOrderSide.SELL,
                "1.65000000", BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("10000.00000000"));

        PnlResult result = TradingPricingSupport.calculatePositionPnlInAccount(
                position, new BigDecimal("1.66000000"), "AUD", "USDT", fxRateService);

        Assertions.assertEquals(0, result.inQuote().compareTo(new BigDecimal("-100.00000000")), "inQuote=-100 AUD");
        // -100 × 0.63 = -63 USDT
        Assertions.assertEquals(0, result.inAccount().compareTo(new BigDecimal("-63.00000000")), "inAccount=-63 USDT");
        Assertions.assertEquals(8, result.inAccount().scale(), "8 位 scale");
        Assertions.assertTrue(result.inAccount().signum() < 0, "inAccount 为负");
    }

    /**
     * Task 7 UT-3b：负 PnL HALF_UP 对负数向绝对值更大方向舍入（验证舍入方向）。
     */
    @Test
    void TC_14B_T7_003b_negativePnl_halfUpRoundsAwayFromZero() {
        FxRateService fxRateService = Mockito.mock(FxRateService.class);
        // 构造一个 9 位、末位 5 需舍入的 fxRate
        Mockito.when(fxRateService.queryRate("AUD", "USDT"))
                .thenReturn(Optional.of(new BigDecimal("0.123456785")));
        // SELL entry=2, mark=3, qty=1 → inQuote = (2-3)*1 = -1 AUD
        TradingPosition position = buildPosition(TradingOrderSide.SELL,
                "2.00000000", BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("1.00000000"));

        PnlResult result = TradingPricingSupport.calculatePositionPnlInAccount(
                position, new BigDecimal("3.00000000"), "AUD", "USDT", fxRateService);

        // -1 × 0.123456785 = -0.123456785 → setScale(8, HALF_UP) = -0.12345679（绝对值更大方向）
        Assertions.assertEquals(0, result.inAccount().compareTo(new BigDecimal("-0.12345679")),
                "负数 HALF_UP 向绝对值更大方向舍入");
    }

    /**
     * Task 7 UT-4：FX 不可用（queryRate empty）→ inAccount/fxRate=null，inQuote/quoteCurrency 仍有值，不抛。
     */
    @Test
    void TC_14B_T7_004_fxUnavailable_inAccountAndFxRateNull_noThrow() {
        FxRateService fxRateService = Mockito.mock(FxRateService.class);
        Mockito.when(fxRateService.queryRate("AUD", "USDT")).thenReturn(Optional.empty());
        TradingPosition position = buildPosition(TradingOrderSide.BUY,
                "1.65000000", BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("10000.00000000"));

        PnlResult result = TradingPricingSupport.calculatePositionPnlInAccount(
                position, new BigDecimal("1.70000000"), "AUD", "USDT", fxRateService);

        Assertions.assertEquals(0, result.inQuote().compareTo(new BigDecimal("500.00000000")), "inQuote 照常");
        Assertions.assertNull(result.inAccount(), "inAccount=null");
        Assertions.assertNull(result.fxRate(), "fxRate=null");
        Assertions.assertEquals("AUD", result.quoteCurrency(), "quoteCurrency 仍有值");
    }

    /**
     * Task 7 UT-5：quoteCurrency=null → 不抛、inAccount/fxRate=null，不查询 FX。
     */
    @Test
    void TC_14B_T7_005_nullQuoteCurrency_noThrow_noFxQuery() {
        FxRateService fxRateService = Mockito.mock(FxRateService.class);
        TradingPosition position = buildPosition(TradingOrderSide.BUY,
                "10000.00000000", BigDecimal.ZERO, BigDecimal.ZERO);

        PnlResult result = TradingPricingSupport.calculatePositionPnlInAccount(
                position, new BigDecimal("10500.00000000"), null, "USDT", fxRateService);

        Assertions.assertEquals(0, result.inQuote().compareTo(new BigDecimal("500.00000000")), "inQuote 照常");
        Assertions.assertNull(result.inAccount(), "inAccount=null");
        Assertions.assertNull(result.fxRate(), "fxRate=null");
        Assertions.assertNull(result.quoteCurrency(), "quoteCurrency=null 留痕");
        Mockito.verifyNoInteractions(fxRateService);
    }

    /**
     * Task 7 UT-5b：quoteCurrency 为 blank（空串 / 空白串）→ 走 {@code isBlank()} 分支
     * （与 null 不同的代码路径），但行为与 null 一致：inAccount/fxRate=null、quoteCurrency 回填 null、
     * 不查询 FX、不抛。
     *
     * <p>注意：生产实现对 blank 输入回填的 quoteCurrency 是 {@code null}（非空串原样透传），
     * 与 inQuote==null 早期短路分支（原样透传）口径不同——此处固化 blank 分支现状。
     * 与 {@code MarginCalculator} 口径是否对称仅作记录，Task 8 不强求统一。
     */
    @Test
    void TC_14B_T7_005b_blankQuoteCurrency_sameAsNull_noThrow_noFxQuery() {
        FxRateService fxRateService = Mockito.mock(FxRateService.class);
        TradingPosition position = buildPosition(TradingOrderSide.BUY,
                "10000.00000000", BigDecimal.ZERO, BigDecimal.ZERO);

        // 空串 "" → isBlank()==true，与 null 不同的入参代码路径
        PnlResult emptyResult = TradingPricingSupport.calculatePositionPnlInAccount(
                position, new BigDecimal("10500.00000000"), "", "USDT", fxRateService);

        Assertions.assertEquals(0, emptyResult.inQuote().compareTo(new BigDecimal("500.00000000")), "inQuote 照常");
        Assertions.assertNull(emptyResult.inAccount(), "inAccount=null（与 null 入参一致）");
        Assertions.assertNull(emptyResult.fxRate(), "fxRate=null（与 null 入参一致）");
        Assertions.assertNull(emptyResult.quoteCurrency(), "quoteCurrency 回填 null（实现对 blank 输入不原样透传）");

        // 空白串 "  " → 同样走 isBlank() 分支，行为一致
        PnlResult whitespaceResult = TradingPricingSupport.calculatePositionPnlInAccount(
                position, new BigDecimal("10500.00000000"), "  ", "USDT", fxRateService);
        Assertions.assertNull(whitespaceResult.inAccount(), "空白串 inAccount=null");
        Assertions.assertNull(whitespaceResult.fxRate(), "空白串 fxRate=null");
        Assertions.assertNull(whitespaceResult.quoteCurrency(), "空白串 quoteCurrency 回填 null");

        Mockito.verifyNoInteractions(fxRateService);
    }

    /**
     * Task 7 UT-6（DRY 证据）：inQuote 与原 calculatePositionPnl 结果一致。
     */
    @Test
    void TC_14B_T7_006_inQuoteEqualsLegacyCalculatePositionPnl() {
        FxRateService fxRateService = Mockito.mock(FxRateService.class);
        Mockito.when(fxRateService.queryRate("AUD", "USDT"))
                .thenReturn(Optional.of(new BigDecimal("0.65000000")));
        // 带 markup 的 BUY 持仓，验证 markup 处理也被复用
        TradingPosition position = buildPosition(TradingOrderSide.BUY,
                "10000.00000000", new BigDecimal("0.50000000"), BigDecimal.ZERO);
        BigDecimal mark = new BigDecimal("10500.00000000");

        BigDecimal legacy = TradingPricingSupport.calculatePositionPnl(position, mark);
        PnlResult result = TradingPricingSupport.calculatePositionPnlInAccount(
                position, mark, "AUD", "USDT", fxRateService);

        Assertions.assertEquals(0, result.inQuote().compareTo(legacy),
                "inQuote 与原 calculatePositionPnl 一致（DRY）");
    }

    /**
     * Task 7 UT-7：inQuote==null（effectiveMarkPrice 缺失）+ 异币种 + FX 可用
     * → 固化「不抛 NPE」+「早期短路不查 FX」行为。
     *
     * <p>生产实现先 {@code calculatePositionPnl}（markPrice=null → inQuote=null），
     * 紧接早期短路 {@code return new PnlResult(null, null, null, quoteCurrency)}，
     * 在 quoteCurrency 判定与 FX 查询之前直接返回：无可换算金额时不查 FX、不返回无意义 fxRate。
     *
     * <p>故 fxRate=null（不再是查到的 0.65），且 {@code verifyNoInteractions(fxRateService)}——
     * 即便 mock 备好了汇率也不会被调用。quoteCurrency 原样透传留痕。
     */
    @Test
    void TC_14B_T7_007_inQuoteNull_crossCurrency_fxAvailable_noThrow() {
        FxRateService fxRateService = Mockito.mock(FxRateService.class);
        // 该 stub 故意备好汇率，但因 inQuote==null 早期短路（在 FX 查询之前 return）实际不会被调用；
        // 用 Mockito.lenient() 声明这是预期内的未使用 stub，防止 strict mock 抛 UnnecessaryStubbingException。
        Mockito.lenient().when(fxRateService.queryRate("AUD", "USDT"))
                .thenReturn(Optional.of(new BigDecimal("0.65000000")));
        TradingPosition position = buildPosition(TradingOrderSide.BUY,
                "1.65000000", BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("10000.00000000"));

        // effectiveMarkPrice=null → inQuote=null（calculatePositionPnl 防守返回 null）→ 早期短路
        PnlResult result = Assertions.assertDoesNotThrow(() ->
                TradingPricingSupport.calculatePositionPnlInAccount(
                        position, null, "AUD", "USDT", fxRateService),
                "markPrice=null 不应抛异常");

        Assertions.assertNull(result.inQuote(), "inQuote=null（markPrice 缺失）");
        Assertions.assertNull(result.inAccount(), "inAccount=null（早期短路）");
        Assertions.assertNull(result.fxRate(), "fxRate=null（早期短路不查 FX，不返回无意义 fxRate）");
        Assertions.assertEquals("AUD", result.quoteCurrency(), "quoteCurrency 原样透传留痕");
        Mockito.verifyNoInteractions(fxRateService);
    }

    /**
     * Task 7 UT-8：inQuote==null（effectiveMarkPrice 缺失）+ 同币种
     * → 早期短路先于同币种判定执行，故 fxRate=null（不再走同币种 fx=ONE 分支），不抛、不查 FX。
     *
     * <p>固化早期短路相对同币种短路的优先级：inQuote==null 时不论币种是否相同，
     * 一律返回 {@code new PnlResult(null, null, null, quoteCurrency)}，quoteCurrency 原样透传。
     */
    @Test
    void TC_14B_T7_008_inQuoteNull_sameCurrency_noThrow_noFxQuery() {
        FxRateService fxRateService = Mockito.mock(FxRateService.class);
        TradingPosition position = buildPosition(TradingOrderSide.BUY,
                "10000.00000000", BigDecimal.ZERO, BigDecimal.ZERO);

        PnlResult result = Assertions.assertDoesNotThrow(() ->
                TradingPricingSupport.calculatePositionPnlInAccount(
                        position, null, "USDT", "USDT", fxRateService),
                "同币种 markPrice=null 不应抛异常");

        Assertions.assertNull(result.inQuote(), "inQuote=null（markPrice 缺失）");
        Assertions.assertNull(result.inAccount(), "inAccount=null（早期短路先于同币种判定）");
        Assertions.assertNull(result.fxRate(), "fxRate=null（早期短路，不再走同币种 fx=ONE）");
        Assertions.assertEquals("USDT", result.quoteCurrency(), "quoteCurrency=USDT 原样透传");
        Mockito.verifyNoInteractions(fxRateService);
    }

    /**
     * Task 7 UT-9：position==null（effectiveMarkPrice 给非 null）+ 异币种 + FX 可用
     * → 与 markPrice==null 对称：{@code calculatePositionPnl} 防守返回 inQuote=null → 早期短路，
     * 不抛、不查 FX、不返回无意义 fxRate，quoteCurrency 原样透传。
     */
    @Test
    void TC_14B_T7_009_nullPosition_noThrow() {
        FxRateService fxRateService = Mockito.mock(FxRateService.class);

        PnlResult result = Assertions.assertDoesNotThrow(() ->
                TradingPricingSupport.calculatePositionPnlInAccount(
                        null, new BigDecimal("1.65000000"), "AUD", "USDT", fxRateService),
                "position=null 不应抛异常");

        Assertions.assertNull(result.inQuote(), "inQuote=null（position 缺失）");
        Assertions.assertNull(result.inAccount(), "inAccount=null（早期短路）");
        Assertions.assertNull(result.fxRate(), "fxRate=null（早期短路不查 FX）");
        Assertions.assertEquals("AUD", result.quoteCurrency(), "quoteCurrency 原样透传");
        Mockito.verifyNoInteractions(fxRateService);
    }

    private static TradingPosition buildPosition(TradingOrderSide side, String entryPrice,
                                                  BigDecimal bidExtraAtOpen, BigDecimal askExtraAtOpen) {
        return buildPosition(side, entryPrice, bidExtraAtOpen, askExtraAtOpen, new BigDecimal("1.00000000"));
    }

    private static TradingPosition buildPosition(TradingOrderSide side, String entryPrice,
                                                  BigDecimal bidExtraAtOpen, BigDecimal askExtraAtOpen,
                                                  BigDecimal quantity) {
        return new TradingPosition(
                1L, 1L, 1L, "BTCUSDT",
                side,
                quantity,
                new BigDecimal(entryPrice),
                BigDecimal.ONE,
                new BigDecimal("0.005000"),
                1,
                new BigDecimal("10.00000000"),
                new BigDecimal("1000.00000000"),
                TradingMarginMode.ISOLATED,
                new BigDecimal("9000.00000000"),
                new BigDecimal("11000.00000000"),
                new BigDecimal("8000.00000000"),
                null, null, null,
                TradingPositionStatus.OPEN,
                BigDecimal.ZERO,
                "vip",
                bidExtraAtOpen,
                askExtraAtOpen,
                OffsetDateTime.now(), null, OffsetDateTime.now()
        );
    }
}
