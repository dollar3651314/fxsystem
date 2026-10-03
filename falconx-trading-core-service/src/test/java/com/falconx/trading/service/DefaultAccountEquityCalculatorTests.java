package com.falconx.trading.service;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.service.impl.DefaultAccountEquityCalculator;
import com.falconx.trading.service.model.AccountMarginState;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * STAGE-14C1 Task 7：{@link DefaultAccountEquityCalculator} 单仓 + 账户级 Equity/MarginLevel 单测。
 *
 * <p>口径对齐 master §3.3 EURAUD 算例（USDT 账户）：
 * <pre>
 *   品种 EURAUD，0.1 lot = 10,000 EUR；entryPrice(AUD)=1.6500；entryFxRate=fx(AUD→USDT)=0.65
 *   notional(AUD)=16,500；notional(USDT)=10,725；mmRate(tier1)=0.005
 *   MM(USDT)=10,725 × 0.005 = 53.625；IM(USDT)=margin(AC)=53.625
 *   markPrice 1.6498 → uPnL(AUD)=-2 → uPnL(USDT)=-1.30（fx 0.65）
 *   账户级（balance=100,frozen=0）：Equity=100-1.30=98.70；MarginLevel=98.70/53.625×100≈184.06%
 * </pre>
 *
 * <p>本测试 mock {@link FxRateService}，不触真实 FX。
 */
class DefaultAccountEquityCalculatorTests {

    /**
     * UT-1（master §3.3 账户级算例）：EURAUD 单仓 → 账户级 Equity=98.70 / MM=53.625 → ≈184%（HEALTHY 区）。
     *
     * <p>口径选择说明：master §3.3 的 184% 是<b>账户级</b>口径（Equity=balance+frozen+ΣuPnL），
     * 故用 {@code computeAccountMarginLevel} 复现该数；单仓口径在 UT-3 单独断言。
     */
    @Test
    void TC_14C1_T7_001_eurAud_accountLevel_matchesMaster_184pct() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        // mark 1.6498，BUY 持仓平仓走 bid；uPnL(AUD)=(1.6498-1.6500)*10000=-2 → ×0.65 = -1.30 USDT
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.65000000")));
        DefaultAccountEquityCalculator calc = new DefaultAccountEquityCalculator(fx);

        TradingPosition pos = eurAudPosition("0.005000", new BigDecimal("53.625"));
        TradingAccount account = usdtAccount(new BigDecimal("100"), BigDecimal.ZERO);

        AccountMarginState state = calc.computeAccountMarginLevel(account,
                List.of(new AccountEquityCalculator.PositionMarkInput(pos, new BigDecimal("1.6498"), "AUD")));

        Assertions.assertEquals(0, state.equity().compareTo(new BigDecimal("98.70")),
                "Equity=balance+frozen+uPnL=100+0-1.30=98.70");
        Assertions.assertEquals(0, state.totalMaintenanceMargin().compareTo(new BigDecimal("53.625")),
                "totalMM=notional(AC)×mmRate=10725×0.005=53.625");
        // 98.70 / 53.625 × 100 = 184.0559... → 2 位 HALF_UP = 184.06
        Assertions.assertEquals(0, state.marginLevel().compareTo(new BigDecimal("184.06")),
                "MarginLevel≈184.06%（master §3.3 标注 184%）");
    }

    /**
     * UT-2（master §3.3 第二例）：EURAUD 暴跌 → 负净值 → MarginLevel 为负（≤30% StopOut 区）。
     *
     * <pre>
     *   EURAUD 跌到 1.5800，AUDUSDT 跌到 0.6300：
     *   uPnL(AUD)=(1.5800-1.6500)*10000=-700 → ×0.63 = -441 USDT
     *   账户级 Equity=100-441=-341；MM(USDT)=notional(QC)×mmRate×fx(实时)
     *   MM=16500×0.005×0.63=51.975（master §3.3 第二例标注值）
     *   MarginLevel=-341/51.975×100 ≈ -656% → STOP_OUT
     * </pre>
     *
     * <p>STAGE-14D2 Task 3 起 MM 用<b>实时 fx</b>（mmRate 冻结）：当时 fx 0.63 → MM=51.975，
     * 与 master §3.3 第二例标注一致（C1 旧实现用冻结 entryFxRate=0.65 得 53.625，D2 已改实时）。
     */
    @Test
    void TC_14C1_T7_002_eurAud_crash_negativeEquity_belowStopOut() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.63000000")));
        DefaultAccountEquityCalculator calc = new DefaultAccountEquityCalculator(fx);

        TradingPosition pos = eurAudPosition("0.005000", new BigDecimal("53.625"));
        TradingAccount account = usdtAccount(new BigDecimal("100"), BigDecimal.ZERO);

        AccountMarginState state = calc.computeAccountMarginLevel(account,
                List.of(new AccountEquityCalculator.PositionMarkInput(pos, new BigDecimal("1.5800"), "AUD")));

        Assertions.assertEquals(0, state.equity().compareTo(new BigDecimal("-341.00")),
                "Equity=100-441=-341（uPnL=-700 AUD×0.63=-441）");
        Assertions.assertEquals(0, state.totalMaintenanceMargin().compareTo(new BigDecimal("51.975")),
                "MM 用实时 fx=0.63：16500×0.005×0.63=51.975（master §3.3 第二例）");
        Assertions.assertTrue(state.marginLevel().signum() < 0, "MarginLevel 为负");
        Assertions.assertTrue(state.marginLevel().compareTo(new BigDecimal("30")) <= 0,
                "MarginLevel 远低于 30% → STOP_OUT 区");
    }

    /**
     * UT-3：单仓口径 MarginLevel（StopOut 主路径）。
     *
     * <pre>
     *   单仓 Equity_i = margin_i(AC) + uPnL_i(AC) = 53.625 + (-1.30) = 52.325
     *   MarginLevel_i = 52.325 / 53.625 × 100 ≈ 97.58%
     * </pre>
     */
    @Test
    void TC_14C1_T7_003_eurAud_singlePosition_marginLevel() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.65000000")));
        DefaultAccountEquityCalculator calc = new DefaultAccountEquityCalculator(fx);

        TradingPosition pos = eurAudPosition("0.005000", new BigDecimal("53.625"));
        TradingAccount account = usdtAccount(new BigDecimal("100"), BigDecimal.ZERO);

        AccountMarginState state = calc.computePositionMarginLevel(pos, account, new BigDecimal("1.6498"), "AUD");

        Assertions.assertEquals(0, state.equity().compareTo(new BigDecimal("52.325")),
                "单仓 Equity=margin(AC)+uPnL=53.625-1.30=52.325");
        Assertions.assertEquals(0, state.totalMaintenanceMargin().compareTo(new BigDecimal("53.625")), "MM_i=53.625");
        // 52.325 / 53.625 × 100 = 97.5757... → 97.58
        Assertions.assertEquals(0, state.marginLevel().compareTo(new BigDecimal("97.58")),
                "MarginLevel_i≈97.58%");
    }

    /**
     * UT-4：同币种（BTCUSDT，QC=USDT=AC，fx=1）单仓 MarginLevel，不触 FX 查询。
     *
     * <pre>
     *   qty=1，entryPrice=10000，entryFxRate=1 → notional(AC)=10000；mmRate=0.004 → MM=40
     *   margin(AC)=80（假设 125x）；mark 10500 BUY → uPnL=+500 → Equity=580
     *   MarginLevel=580/40×100=1450%
     * </pre>
     */
    @Test
    void TC_14C1_T7_004_sameCurrency_fxOne_singlePosition() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        DefaultAccountEquityCalculator calc = new DefaultAccountEquityCalculator(fx);

        TradingPosition pos = btcUsdtPosition("0.004000", new BigDecimal("1"),
                new BigDecimal("10000"), new BigDecimal("80"));
        TradingAccount account = usdtAccount(new BigDecimal("1000"), BigDecimal.ZERO);

        AccountMarginState state = calc.computePositionMarginLevel(pos, account, new BigDecimal("10500"), "USDT");

        Assertions.assertEquals(0, state.totalMaintenanceMargin().compareTo(new BigDecimal("40.00000000")),
                "MM=10000×0.004=40");
        Assertions.assertEquals(0, state.equity().compareTo(new BigDecimal("580.00000000")),
                "Equity=margin(80)+uPnL(500)=580");
        Assertions.assertEquals(0, state.marginLevel().compareTo(new BigDecimal("1450.00")), "MarginLevel=1450%");
        // 同币种短路：calculatePositionPnlInAccount 内部不查 FX
        Mockito.verifyNoInteractions(fx);
    }

    /**
     * UT-5：MM 用<b>开仓冻结 mmRateAtOpen</b>——不同 mmRate 产出不同 MM / MarginLevel（证明读冻结值非硬码）。
     */
    @Test
    void TC_14C1_T7_005_mmUsesFrozenMmRateAtOpen() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        DefaultAccountEquityCalculator calc = new DefaultAccountEquityCalculator(fx);
        TradingAccount account = usdtAccount(new BigDecimal("1000"), BigDecimal.ZERO);

        // 同一持仓参数，仅 mmRateAtOpen 不同：0.004 vs 0.010
        TradingPosition lowMm = btcUsdtPosition("0.004000", new BigDecimal("1"),
                new BigDecimal("10000"), new BigDecimal("80"));
        TradingPosition highMm = btcUsdtPosition("0.010000", new BigDecimal("1"),
                new BigDecimal("10000"), new BigDecimal("80"));

        AccountMarginState low = calc.computePositionMarginLevel(lowMm, account, new BigDecimal("10500"), "USDT");
        AccountMarginState high = calc.computePositionMarginLevel(highMm, account, new BigDecimal("10500"), "USDT");

        Assertions.assertEquals(0, low.totalMaintenanceMargin().compareTo(new BigDecimal("40.00000000")),
                "0.004 → MM=40");
        Assertions.assertEquals(0, high.totalMaintenanceMargin().compareTo(new BigDecimal("100.00000000")),
                "0.010 → MM=100");
        // Equity 相同（580），MM 不同 → MarginLevel 不同（580/40=1450% vs 580/100=580%）
        Assertions.assertEquals(0, low.marginLevel().compareTo(new BigDecimal("1450.00")), "低 MM → 1450%");
        Assertions.assertEquals(0, high.marginLevel().compareTo(new BigDecimal("580.00")), "高 MM → 580%");
    }

    /**
     * UT-6：空仓位账户级 → totalMM=0、marginLevel=null（语义「无持仓」），不抛。
     */
    @Test
    void TC_14C1_T7_006_emptyPositions_accountLevel_totalMmZero_marginLevelNull() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        DefaultAccountEquityCalculator calc = new DefaultAccountEquityCalculator(fx);
        TradingAccount account = usdtAccount(new BigDecimal("1000"), BigDecimal.ZERO);

        AccountMarginState state = calc.computeAccountMarginLevel(account, List.of());

        Assertions.assertEquals(0, state.totalMaintenanceMargin().compareTo(BigDecimal.ZERO), "空仓位 totalMM=0");
        Assertions.assertNull(state.marginLevel(), "totalMM=0 → marginLevel=null（无持仓语义）");
        // Equity 仍可给出（balance+frozen），不强制 null
        Assertions.assertEquals(0, state.equity().compareTo(new BigDecimal("1000")), "Equity=balance+frozen=1000");
        Mockito.verifyNoInteractions(fx);
    }

    /**
     * UT-7：FX 不可用（queryRate empty）→ uPnL inAccount=null → equity/marginLevel=null（caller 降级，不强平），
     * totalMM 仍按可算部分给出（用开仓冻结口径，不依赖实时 FX）。
     */
    @Test
    void TC_14C1_T7_007_fxUnavailable_marginLevelNull_degrade() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.empty());
        DefaultAccountEquityCalculator calc = new DefaultAccountEquityCalculator(fx);

        TradingPosition pos = eurAudPosition("0.005000", new BigDecimal("53.625"));
        TradingAccount account = usdtAccount(new BigDecimal("100"), BigDecimal.ZERO);

        AccountMarginState state = calc.computePositionMarginLevel(pos, account, new BigDecimal("1.6498"), "AUD");

        Assertions.assertNull(state.equity(), "FX 不可用 → equity=null");
        Assertions.assertNull(state.marginLevel(), "FX 不可用 → marginLevel=null（降级，不强平）");
        Assertions.assertEquals(0, state.totalMaintenanceMargin().compareTo(new BigDecimal("53.625")),
                "MM 用开仓冻结口径，不依赖实时 FX，仍可算");
    }

    /**
     * UT-8：账户级中某仓 FX 不可用 → 整体 equity/marginLevel=null（任一仓不可换算即整体降级），
     * totalMM 仍按全部仓位冻结口径汇总。
     */
    @Test
    void TC_14C1_T7_008_accountLevel_oneFxUnavailable_degrades() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        // BTCUSDT 同币种可算；EURAUD FX 不可用
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.empty());
        DefaultAccountEquityCalculator calc = new DefaultAccountEquityCalculator(fx);

        TradingPosition btc = btcUsdtPosition("0.004000", new BigDecimal("1"),
                new BigDecimal("10000"), new BigDecimal("80"));
        TradingPosition eurAud = eurAudPosition("0.005000", new BigDecimal("53.625"));
        TradingAccount account = usdtAccount(new BigDecimal("1000"), BigDecimal.ZERO);

        AccountMarginState state = calc.computeAccountMarginLevel(account, List.of(
                new AccountEquityCalculator.PositionMarkInput(btc, new BigDecimal("10500"), "USDT"),
                new AccountEquityCalculator.PositionMarkInput(eurAud, new BigDecimal("1.6498"), "AUD")));

        Assertions.assertNull(state.equity(), "任一仓 FX 不可用 → 整体 equity=null");
        Assertions.assertNull(state.marginLevel(), "整体 marginLevel=null（降级）");
        // totalMM = 40（BTC）+ 53.625（EURAUD 冻结口径）= 93.625
        Assertions.assertEquals(0, state.totalMaintenanceMargin().compareTo(new BigDecimal("93.625")),
                "totalMM=40+53.625=93.625（冻结口径不依赖 FX）");
    }

    /**
     * UT-9（D2 实时 MM）：MM 用<b>实时 fx</b>——queryRate 返回 0.70（≠ entryFxRate 0.65）→ MM 用 0.70 重算，
     * 证明 MM 随实时 FX 变化（mmRate 仍冻结 mmRateAtOpen）。
     *
     * <pre>
     *   notional(QC)=qty×entryPrice=10000×1.6500=16500 AUD
     *   MM(AC)=notional(QC)×mmRateAtOpen×fx(QC→AC)=16500×0.005×0.70=57.75
     *   （冻结口径 entryFxRate=0.65 时为 53.625；两者不同 → 证实时生效）
     * </pre>
     */
    @Test
    void TC_14D2_T3_009_maintenanceMargin_usesRealtimeFx() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        // 实时 fx=0.70 ≠ 开仓冻结 entryFxRate=0.65
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.of(new BigDecimal("0.70000000")));
        DefaultAccountEquityCalculator calc = new DefaultAccountEquityCalculator(fx);

        TradingPosition pos = eurAudPosition("0.005000", new BigDecimal("53.625"));
        TradingAccount account = usdtAccount(new BigDecimal("100"), BigDecimal.ZERO);

        AccountMarginState state = calc.computeAccountMarginLevel(account,
                List.of(new AccountEquityCalculator.PositionMarkInput(pos, new BigDecimal("1.6500"), "AUD")));

        Assertions.assertEquals(0, state.totalMaintenanceMargin().compareTo(new BigDecimal("57.75")),
                "MM 用实时 fx=0.70：16500×0.005×0.70=57.75（≠ 冻结 0.65 口径 53.625）");
    }

    /**
     * UT-10（D2 实时 MM 降级）：FX 不可用（queryRate empty）→ MM 降级用 <b>entryFxRate</b>（0.65，最后已知），
     * 不返回 0、不停 MM（master §3.5.5 用最后 rate）。
     *
     * <p>用 {@code computeAccountMarginLevel} 单独验证 totalMM（equity 因 uPnL 不可换算降级 null，与本断言无关）。
     */
    @Test
    void TC_14D2_T3_010_maintenanceMargin_fxUnavailable_degradesToEntryFxRate() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        Mockito.when(fx.queryRate("AUD", "USDT")).thenReturn(Optional.empty());
        DefaultAccountEquityCalculator calc = new DefaultAccountEquityCalculator(fx);

        TradingPosition pos = eurAudPosition("0.005000", new BigDecimal("53.625"));
        TradingAccount account = usdtAccount(new BigDecimal("100"), BigDecimal.ZERO);

        AccountMarginState state = calc.computeAccountMarginLevel(account,
                List.of(new AccountEquityCalculator.PositionMarkInput(pos, new BigDecimal("1.6500"), "AUD")));

        Assertions.assertEquals(0, state.totalMaintenanceMargin().compareTo(new BigDecimal("53.625")),
                "FX 不可用 → MM 降级用 entryFxRate=0.65：16500×0.005×0.65=53.625（不停 MM）");
    }

    /**
     * UT-11（D2 实时 MM 同币种）：QC==AC（BTCUSDT，USDT）→ fx=1，不查 FX，MM=notional(QC)×mmRate。
     *
     * <pre>
     *   notional(QC)=1×10000=10000；MM=10000×0.004×1=40
     * </pre>
     */
    @Test
    void TC_14D2_T3_011_maintenanceMargin_sameCurrency_fxOne() {
        FxRateService fx = Mockito.mock(FxRateService.class);
        DefaultAccountEquityCalculator calc = new DefaultAccountEquityCalculator(fx);

        TradingPosition pos = btcUsdtPosition("0.004000", new BigDecimal("1"),
                new BigDecimal("10000"), new BigDecimal("80"));
        TradingAccount account = usdtAccount(new BigDecimal("1000"), BigDecimal.ZERO);

        AccountMarginState state = calc.computeAccountMarginLevel(account,
                List.of(new AccountEquityCalculator.PositionMarkInput(pos, new BigDecimal("10000"), "USDT")));

        Assertions.assertEquals(0, state.totalMaintenanceMargin().compareTo(new BigDecimal("40.00")),
                "同币种 fx=1：10000×0.004×1=40");
        // 同币种不查 FX
        Mockito.verifyNoInteractions(fx);
    }

    // --------------------------------------------------------------------- helpers

    /** EURAUD 0.1 lot：qty=10000，entryPrice=1.6500，entryFxRate(AUD→USDT)=0.65，BUY。 */
    private static TradingPosition eurAudPosition(String mmRateAtOpen, BigDecimal marginAc) {
        return new TradingPosition(
                1L, 1L, 1L, "EURAUD",
                TradingOrderSide.BUY,
                new BigDecimal("10000"),            // quantity (EUR)
                new BigDecimal("1.6500"),           // entryPrice (AUD)
                new BigDecimal("0.65000000"),       // entryFxRate (AUD→USDT)
                new BigDecimal(mmRateAtOpen),       // mmRateAtOpen
                1,                                  // tierNoAtOpen
                new BigDecimal("200"),              // leverage
                marginAc,                           // margin (AC = USDT)
                TradingMarginMode.ISOLATED,
                new BigDecimal("1.6000"),           // liquidationPrice
                null, null, null, null, null,
                TradingPositionStatus.OPEN,
                BigDecimal.ZERO,                    // openFeeRate
                "default",
                BigDecimal.ZERO, BigDecimal.ZERO,   // bid/askExtraAtOpen
                OffsetDateTime.now(), null, OffsetDateTime.now()
        );
    }

    /** BTCUSDT 同币种：entryFxRate=1，BUY。 */
    private static TradingPosition btcUsdtPosition(String mmRateAtOpen, BigDecimal qty,
                                                   BigDecimal entryPrice, BigDecimal marginAc) {
        return new TradingPosition(
                2L, 2L, 1L, "BTCUSDT",
                TradingOrderSide.BUY,
                qty,
                entryPrice,
                BigDecimal.ONE,                     // entryFxRate (USDT→USDT)
                new BigDecimal(mmRateAtOpen),
                1,
                new BigDecimal("125"),
                marginAc,
                TradingMarginMode.ISOLATED,
                new BigDecimal("9000"),
                null, null, null, null, null,
                TradingPositionStatus.OPEN,
                BigDecimal.ZERO,
                "default",
                BigDecimal.ZERO, BigDecimal.ZERO,
                OffsetDateTime.now(), null, OffsetDateTime.now()
        );
    }

    private static TradingAccount usdtAccount(BigDecimal balance, BigDecimal frozen) {
        return new TradingAccount(
                1L, 1L, "USDT",
                balance, frozen, BigDecimal.ZERO,
                TradingMarginMode.ISOLATED,
                null, null,
                OffsetDateTime.now(), OffsetDateTime.now()
        );
    }
}
