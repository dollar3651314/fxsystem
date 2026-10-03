package com.falconx.trading.service.impl;

import com.falconx.trading.calculator.LiquidationPriceCalculator;
import com.falconx.trading.calculator.MarginCalculator;
import com.falconx.trading.calculator.MarginResult;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingQuoteQualityStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.entity.TradingRiskControlActionType;
import com.falconx.trading.repository.MarketSymbolSpecRepository;
import com.falconx.trading.repository.TradingPositionRepository;
import com.falconx.trading.repository.TradingRiskConfigRepository;
import com.falconx.trading.repository.TradingRiskControlActionRepository;
import com.falconx.trading.repository.TradingScheduleSnapshotRepository;
import com.falconx.trading.service.TradingScheduleService;
import com.falconx.trading.service.model.TradingRiskDecision;
import com.falconx.trading.service.model.TradingScheduleSnapshot;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DefaultTradingRiskService BBook 风控动作检查单元测试。
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class DefaultTradingRiskServiceRiskControlTests {

    @Mock private TradingCoreServiceProperties properties;
    @Mock private MarginCalculator marginCalculator;
    @Mock private LiquidationPriceCalculator liquidationPriceCalculator;
    @Mock private TradingScheduleService tradingScheduleService;
    @Mock private TradingScheduleSnapshotRepository tradingScheduleSnapshotRepository;
    @Mock private TradingRiskConfigRepository tradingRiskConfigRepository;
    @Mock private TradingPositionRepository tradingPositionRepository;
    @Mock private TradingRiskControlActionRepository tradingRiskControlActionRepository;
    @Mock private MarketSymbolSpecRepository marketSymbolSpecRepository;
    @Mock private com.falconx.trading.repository.TradingUserRiskThresholdRepository tradingUserRiskThresholdRepository;
    @Mock private com.falconx.trading.repository.TradingQuoteSnapshotRepository tradingQuoteSnapshotRepository;
    @Mock private com.falconx.trading.service.FxRateService fxRateService;
    @Mock private com.falconx.trading.service.LeverageTierResolver leverageTierResolver;
    @Mock private com.falconx.trading.repository.FxPauseBehaviorRepository fxPauseBehaviorRepository;
    @Mock private com.falconx.trading.repository.RedisTradingRiskSwitchCache riskSwitchCache;

    // ─── REJECT_OPEN ─────────────────────────────────────────────────────────

    @Test
    void shouldRejectOrderWhenSymbolHasActiveRejectOpen() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecks();
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.of(TradingRiskControlActionType.REJECT_OPEN));

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertFalse(decision.accepted());
        Assertions.assertEquals("BBOOK_RISK_OPEN_REJECTED", decision.rejectReason());
    }

    // ─── REDUCE_ONLY ─────────────────────────────────────────────────────────

    @Test
    void shouldRejectOpenWhenSymbolIsInReduceOnlyMode() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecks();
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.of(TradingRiskControlActionType.REDUCE_ONLY));

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertFalse(decision.accepted());
        Assertions.assertEquals("BBOOK_RISK_REDUCE_ONLY", decision.rejectReason());
    }

    // ─── SUSPEND_SYMBOL ──────────────────────────────────────────────────────

    @Test
    void shouldRejectOrderWhenSymbolIsSuspended() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecks();
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.of(TradingRiskControlActionType.SUSPEND_SYMBOL));

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertFalse(decision.accepted());
        Assertions.assertEquals("SYMBOL_TRADING_SUSPENDED", decision.rejectReason());
    }

    // ─── GLOBAL_PAUSE / FX_PAUSED 按类目（STAGE-14C2 Task 6）────────────────────

    /**
     * Task 6：GLOBAL_PAUSE 激活 + forex(cat2) allow_open=false → reject GLOBAL_PAUSE_ACTIVE（30087）。
     */
    @Test
    void shouldRejectOpenWhenPauseActiveAndCategoryDisallowsOpen() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecksWithCategory(2);
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(true);
        Mockito.when(fxPauseBehaviorRepository.findByCategory(2))
                .thenReturn(Optional.of(new com.falconx.trading.entity.FxPauseBehavior(
                        2, "forex", false, true, false)));

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertFalse(decision.accepted());
        Assertions.assertEquals("GLOBAL_PAUSE_ACTIVE", decision.rejectReason());
        // pause 已按类目拒，不应再查品种级别动作
        Mockito.verify(tradingRiskControlActionRepository, Mockito.never())
                .findMostSevereActiveBySymbol(Mockito.any());
    }

    /**
     * Task 6：GLOBAL_PAUSE 激活 + crypto(cat1) allow_open=true → 不因 pause 拒（放行，继续后续校验链路）。
     */
    @Test
    void shouldNotRejectOpenWhenPauseActiveButCategoryAllowsOpen() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecksWithCategory(1);
        stubAcceptDownstreamAfterRiskControl();
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(true);
        Mockito.when(fxPauseBehaviorRepository.findByCategory(1))
                .thenReturn(Optional.of(new com.falconx.trading.entity.FxPauseBehavior(
                        1, "crypto", true, true, true)));
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.empty());

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertTrue(decision.accepted(), "crypto allow_open=true → pause 不拒，应放行到 accept");
    }

    /**
     * Task 6 保守降级：pause 激活 + category==null（过渡期旧快照）→ 回退原一刀切全拒（BBOOK_RISK_GLOBAL_PAUSE）。
     */
    @Test
    void shouldFallbackToBlanketPauseRejectWhenCategoryNull() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecksWithCategory(null);
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(true);

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertFalse(decision.accepted());
        Assertions.assertEquals("BBOOK_RISK_GLOBAL_PAUSE", decision.rejectReason());
        // category 缺失保守全拒，不应查 behavior，也不应查品种级别动作
        Mockito.verify(fxPauseBehaviorRepository, Mockito.never()).findByCategory(Mockito.anyInt());
        Mockito.verify(tradingRiskControlActionRepository, Mockito.never())
                .findMostSevereActiveBySymbol(Mockito.any());
    }

    /**
     * Task 6 保守降级：pause 激活 + behavior 缺失（findByCategory empty）→ 回退原一刀切全拒。
     */
    @Test
    void shouldFallbackToBlanketPauseRejectWhenBehaviorMissing() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecksWithCategory(2);
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(true);
        Mockito.when(fxPauseBehaviorRepository.findByCategory(2)).thenReturn(Optional.empty());

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertFalse(decision.accepted());
        Assertions.assertEquals("BBOOK_RISK_GLOBAL_PAUSE", decision.rejectReason());
        Mockito.verify(tradingRiskControlActionRepository, Mockito.never())
                .findMostSevereActiveBySymbol(Mockito.any());
    }

    /**
     * Task 6：非 pause 态（hasActiveGlobalPause=false）→ 完全不受 behavior 影响，behavior 不查询。
     */
    @Test
    void shouldNotConsultFxPauseBehaviorWhenPauseInactive() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecksWithCategory(2);
        stubAcceptDownstreamAfterRiskControl();
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.empty());

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertTrue(decision.accepted());
        // 非 pause 态不查 behavior
        Mockito.verify(fxPauseBehaviorRepository, Mockito.never()).findByCategory(Mockito.anyInt());
    }

    // ─── 无风控限制时正常通过 ─────────────────────────────────────────────────

    @Test
    void shouldPassRiskControlWhenNoActiveActions() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecks();
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.empty());
        // 保证金计算桩，让服务顺利到达 accepted 路径
        Mockito.when(properties.getMaxLeverage()).thenReturn(new BigDecimal("100"));
        // STAGE-14B Task 6：calculateInitialMargin 改为多币种签名，返回 MarginResult（USDT 账户/USDT 计价，fx=1）
        Mockito.when(marginCalculator.calculateInitialMargin(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ONE));
        // STAGE-14B Task 8：calculateFee 改为多币种签名，返回 MarginResult（USDT 账户/USDT 计价，fx=1）
        Mockito.when(marginCalculator.calculateFee(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("1"), new BigDecimal("1"), BigDecimal.ONE));
        Mockito.when(properties.getDefaultFeeRate()).thenReturn(new BigDecimal("0.0005"));
        Mockito.when(tradingRiskConfigRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        Mockito.when(liquidationPriceCalculator.calculate(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(new BigDecimal("30000"));
        Mockito.when(properties.getMaintenanceMarginRate()).thenReturn(new BigDecimal("0.005"));
        stubDefaultTier();

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertTrue(decision.accepted());
    }

    // ─── PROD 精度统一 切片 3：市价单 quantity 精度校验 ───────────────────────

    /**
     * qtyPrecision=2 时 quantity=0.123（3 位小数）→ reject QTY_PRECISION_EXCEEDED，
     * 且不进入保证金/FX 计算（精度校验在 qty min/max 之后、FX 之前）。
     */
    @Test
    void shouldRejectMarketOrderWhenQuantityScaleExceedsQtyPrecision() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecksWithQtyPrecision(2);
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskConfigRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());

        TradingRiskDecision decision = service.evaluateMarketOrder(
                commandWithQuantity(new BigDecimal("0.123")), account("10000"), freshQuote());

        Assertions.assertFalse(decision.accepted());
        Assertions.assertEquals("QTY_PRECISION_EXCEEDED", decision.rejectReason());
        Mockito.verify(marginCalculator, Mockito.never()).calculateInitialMargin(
                Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());
    }

    /**
     * qtyPrecision=2 时 quantity=0.12（恰好 2 位小数）→ 不因精度被拒（放行到 accept）。
     */
    @Test
    void shouldAcceptMarketOrderWhenQuantityScaleEqualsQtyPrecision() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecksWithQtyPrecision(2);
        stubAcceptDownstreamAfterRiskControl();
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.empty());

        TradingRiskDecision decision = service.evaluateMarketOrder(
                commandWithQuantity(new BigDecimal("0.12")), account("10000"), freshQuote());

        Assertions.assertTrue(decision.accepted(), "quantity=0.12 == qtyPrecision=2 不应因精度被拒");
    }

    /**
     * qtyPrecision=2 时 quantity=100（整数，去尾零后 scale=0）→ 不因精度被拒（放行到 accept）。
     */
    @Test
    void shouldAcceptMarketOrderWhenQuantityIsIntegerUnderQtyPrecision() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecksWithQtyPrecision(2);
        stubAcceptDownstreamAfterRiskControl();
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.empty());

        TradingRiskDecision decision = service.evaluateMarketOrder(
                commandWithQuantity(new BigDecimal("100")), account("10000"), freshQuote());

        Assertions.assertTrue(decision.accepted(), "整数 quantity=100 (scale=0) 不应因精度被拒");
    }

    /**
     * 指定 qtyPrecision 的 passthrough 桩（pricePrecision 保持 8，maxQty 放大到 1e9 容纳整数 100 用例）。
     */
    private void stubPassthroughChecksWithQtyPrecision(int qtyPrecision) {
        Mockito.when(tradingScheduleSnapshotRepository.findBySymbol("BTCUSDT"))
                .thenReturn(Optional.of(Mockito.mock(TradingScheduleSnapshot.class)));
        Mockito.when(tradingScheduleService.isOpenAllowed(Mockito.eq("BTCUSDT"), Mockito.any()))
                .thenReturn(true);
        Mockito.when(properties.getMaxLeverage()).thenReturn(new BigDecimal("100"));
        Mockito.when(marketSymbolSpecRepository.findByPlatformSymbol("BTCUSDT"))
                .thenReturn(Optional.of(new com.falconx.market.contract.SymbolSpec(
                        "BTCUSDT",
                        100,
                        new BigDecimal("0.0005"),
                        BigDecimal.ZERO,
                        new BigDecimal("0.00000001"),
                        new BigDecimal("1000000000"),
                        BigDecimal.ZERO,
                        8,
                        qtyPrecision,
                        "BTC",
                        "USDT",
                        1
                )));
    }

    private PlaceMarketOrderCommand commandWithQuantity(BigDecimal quantity) {
        return new PlaceMarketOrderCommand(
                1001L,
                "BTCUSDT",
                TradingOrderSide.BUY,
                quantity,
                new BigDecimal("10"),
                TradingMarginMode.ISOLATED,
                null,
                null,
                "client-order-qty-" + quantity.toPlainString()
        );
    }

    // ─── 工具方法 ────────────────────────────────────────────────────────────

    private void stubPassthroughChecks() {
        stubPassthroughChecksWithCategory(1);
    }

    /**
     * STAGE-14C2 Task 6：可指定 SymbolSpec.category 的 passthrough 桩，供 FX_PAUSED 按类目用例
     * 构造 forex(2) / crypto(1) / null（过渡期旧快照）等不同 category 路径。
     */
    private void stubPassthroughChecksWithCategory(Integer category) {
        Mockito.when(tradingScheduleSnapshotRepository.findBySymbol("BTCUSDT"))
                .thenReturn(Optional.of(Mockito.mock(TradingScheduleSnapshot.class)));
        Mockito.when(tradingScheduleService.isOpenAllowed(Mockito.eq("BTCUSDT"), Mockito.any()))
                .thenReturn(true);
        // BBook 风控检查在 leverage 检查之后，所以必须让 leverage 检查通过
        Mockito.when(properties.getMaxLeverage()).thenReturn(new BigDecimal("100"));
        // STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.2: 必须 mock SymbolSpec，否则 SYMBOL_SPEC_NOT_FOUND 拒单
        Mockito.when(marketSymbolSpecRepository.findByPlatformSymbol("BTCUSDT"))
                .thenReturn(Optional.of(new com.falconx.market.contract.SymbolSpec(
                        "BTCUSDT",
                        100,
                        new BigDecimal("0.0005"),
                        BigDecimal.ZERO,
                        new BigDecimal("0.00000001"),
                        new BigDecimal("1000000"),
                        BigDecimal.ZERO,
                        8,
                        8,
                        "BTC",
                        "USDT",
                        category
                )));
    }

    /**
     * STAGE-14C2 Task 6：把风控检查之后到 accept 的下游链路（margin/fee FX=1、tier、riskConfig）打通，
     * 供「pause 激活但类目允许开仓 / 非 pause 态」用例验证最终 accept。
     */
    private void stubAcceptDownstreamAfterRiskControl() {
        Mockito.when(tradingRiskConfigRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        Mockito.when(marginCalculator.calculateInitialMargin(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ONE));
        Mockito.when(marginCalculator.calculateFee(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("1"), new BigDecimal("1"), BigDecimal.ONE));
        Mockito.when(properties.getDefaultFeeRate()).thenReturn(new BigDecimal("0.0005"));
        Mockito.when(liquidationPriceCalculator.calculate(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(new BigDecimal("30000"));
        stubDefaultTier();
    }

    private DefaultTradingRiskService createService() {
        return new DefaultTradingRiskService(
                properties,
                marginCalculator,
                liquidationPriceCalculator,
                tradingScheduleService,
                tradingScheduleSnapshotRepository,
                tradingRiskConfigRepository,
                tradingPositionRepository,
                tradingRiskControlActionRepository,
                marketSymbolSpecRepository,
                tradingUserRiskThresholdRepository,
                tradingQuoteSnapshotRepository,
                // STAGE-12-GROUP-MARKUP：测试中无组加点配置，find 默认空 Optional → fillPrice 走基准价
                stubEmptyGroupMarkupService(),
                fxRateService,
                leverageTierResolver,
                fxPauseBehaviorRepository,
                riskSwitchCache
        );
    }

    /**
     * STAGE-14C1 Task 6：通用 tier 桩，让 accept 路径默认命中一个宽松档位
     * （maxLeverage=100、mmRate=0.005、最高档 upper=null），既有 accept 用例无需逐个改动。
     * 需要校验拒单/不同 mmRate 的用例自行覆盖此桩。
     */
    private void stubDefaultTier() {
        Mockito.when(leverageTierResolver.resolve(Mockito.anyString(), Mockito.any(), Mockito.anyString()))
                .thenReturn(Optional.of(new com.falconx.trading.service.model.LeverageTier(
                        1, 100, new BigDecimal("0.005000"), BigDecimal.ZERO, null)));
    }

    private static com.falconx.trading.service.TradingGroupMarkupService stubEmptyGroupMarkupService() {
        com.falconx.trading.service.TradingGroupMarkupService svc =
                org.mockito.Mockito.mock(com.falconx.trading.service.TradingGroupMarkupService.class);
        org.mockito.Mockito.when(svc.find(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString()))
                .thenReturn(java.util.Optional.empty());
        return svc;
    }

    private PlaceMarketOrderCommand command() {
        return new PlaceMarketOrderCommand(
                1001L,
                "BTCUSDT",
                TradingOrderSide.BUY,
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                TradingMarginMode.ISOLATED,
                null,
                null,
                "client-order-001"
        );
    }

    private TradingQuoteSnapshot freshQuote() {
        return new TradingQuoteSnapshot(
                "BTCUSDT",
                new BigDecimal("39990"),
                new BigDecimal("40010"),
                new BigDecimal("40000"),
                OffsetDateTime.now(),
                "test",
                false,
                TradingQuoteQualityStatus.FRESH,
                null
        );
    }

    private TradingAccount account(String available) {
        return account(available, TradingMarginMode.ISOLATED);
    }

    private TradingAccount account(String available, TradingMarginMode accountMode) {
        BigDecimal bal = new BigDecimal(available);
        return new TradingAccount(1L, 1001L, "USDT", bal, BigDecimal.ZERO, BigDecimal.ZERO,
                accountMode, null, null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private PlaceMarketOrderCommand commandWithMarginMode(TradingMarginMode marginMode) {
        return new PlaceMarketOrderCommand(
                1001L,
                "BTCUSDT",
                TradingOrderSide.BUY,
                new BigDecimal("0.01"),
                new BigDecimal("10"),
                marginMode,
                null,
                null,
                "client-order-margin-" + (marginMode == null ? "null" : marginMode.name())
        );
    }

    // ─── STAGE-0-INFRA-EXT-01：保证金模式 inheritance ─────────────────────

    @Test
    void shouldInheritIsolatedFromAccountWhenRequestMarginModeIsNull() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecks();
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.empty());
        Mockito.when(properties.getMaxLeverage()).thenReturn(new BigDecimal("100"));
        // STAGE-14B Task 6：多币种签名，返回 MarginResult（fx=1）
        Mockito.when(marginCalculator.calculateInitialMargin(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ONE));
        // STAGE-14B Task 8：calculateFee 改为多币种签名，返回 MarginResult（USDT 账户/USDT 计价，fx=1）
        Mockito.when(marginCalculator.calculateFee(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("1"), new BigDecimal("1"), BigDecimal.ONE));
        Mockito.when(properties.getDefaultFeeRate()).thenReturn(new BigDecimal("0.0005"));
        Mockito.when(tradingRiskConfigRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        Mockito.when(liquidationPriceCalculator.calculate(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(new BigDecimal("30000"));
        Mockito.when(properties.getMaintenanceMarginRate()).thenReturn(new BigDecimal("0.005"));
        stubDefaultTier();

        TradingRiskDecision decision = service.evaluateMarketOrder(
                commandWithMarginMode(null),
                account("10000", TradingMarginMode.ISOLATED),
                freshQuote()
        );

        Assertions.assertTrue(decision.accepted(), "request marginMode=null + account=ISOLATED 应通过");
    }

    /**
     * STAGE-14D2 Task 2 改：account=CROSS + cross_mode.enabled=false → reject CROSS_MODE_NOT_ENABLED（30088）。
     * （原 D1 行为是 MARGIN_MODE_NOT_SUPPORTED；D2 放开后 CROSS 改走开关 gate。）
     */
    @Test
    void shouldRejectInheritedCrossWhenCrossModeDisabled() {
        DefaultTradingRiskService service = createService();
        // CROSS gate 在 marginMode 检查（quantity/leverage 之后）就被拒，所以只需 schedule + isOpenAllowed stub。
        Mockito.when(tradingScheduleSnapshotRepository.findBySymbol("BTCUSDT"))
                .thenReturn(Optional.of(Mockito.mock(TradingScheduleSnapshot.class)));
        Mockito.when(tradingScheduleService.isOpenAllowed(Mockito.eq("BTCUSDT"), Mockito.any()))
                .thenReturn(true);
        Mockito.when(riskSwitchCache.isEnabled(
                com.falconx.trading.entity.TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false)).thenReturn(false);

        // request 不传 marginMode + account 为 CROSS → inherit 后仍是 CROSS → gate 关闭 → 30088
        TradingRiskDecision decision = service.evaluateMarketOrder(
                commandWithMarginMode(null),
                account("10000", TradingMarginMode.CROSS),
                freshQuote()
        );

        Assertions.assertFalse(decision.accepted());
        Assertions.assertEquals("CROSS_MODE_NOT_ENABLED", decision.rejectReason());
    }

    /**
     * STAGE-14D2 Task 2 改：request 显式 CROSS + cross_mode.enabled=false → reject CROSS_MODE_NOT_ENABLED（30088）。
     */
    @Test
    void shouldRejectRequestCrossWhenCrossModeDisabled() {
        DefaultTradingRiskService service = createService();
        Mockito.when(tradingScheduleSnapshotRepository.findBySymbol("BTCUSDT"))
                .thenReturn(Optional.of(Mockito.mock(TradingScheduleSnapshot.class)));
        Mockito.when(tradingScheduleService.isOpenAllowed(Mockito.eq("BTCUSDT"), Mockito.any()))
                .thenReturn(true);
        Mockito.when(riskSwitchCache.isEnabled(
                com.falconx.trading.entity.TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false)).thenReturn(false);

        // request 显式传 CROSS + account 为 ISOLATED → request 优先 → gate 关闭 → 30088
        TradingRiskDecision decision = service.evaluateMarketOrder(
                commandWithMarginMode(TradingMarginMode.CROSS),
                account("10000", TradingMarginMode.ISOLATED),
                freshQuote()
        );

        Assertions.assertFalse(decision.accepted());
        Assertions.assertEquals("CROSS_MODE_NOT_ENABLED", decision.rejectReason());
    }

    // ─── STAGE-14D2 Task 2：CROSS 开仓放开（cross_mode.enabled gate）+ liquidationPrice=null ─────

    /**
     * Task 2：account=CROSS + cross_mode.enabled=true → 通过；decision.liquidationPrice=null
     * （master §3.2 CROSS 不存单仓 liqPrice，账户级 MarginLevel 触发），且 IM 冻结同 ISOLATED
     * （margin=margin.inAccount），mmRate/tierNo 仍冻结。强平价计算器不应被调用。
     */
    @Test
    void shouldAcceptCrossOpenWithNullLiquidationPriceWhenCrossModeEnabled() {
        DefaultTradingRiskService service = createService();
        stubAcceptPathExceptTier();
        Mockito.when(riskSwitchCache.isEnabled(
                com.falconx.trading.entity.TradingRiskSwitch.KEY_CROSS_MODE_ENABLED, false)).thenReturn(true);
        stubDefaultTier();

        // request 显式 CROSS（account 为 CROSS 同效）；命中宽松档（lev=10 ≤ 100）
        TradingRiskDecision decision = service.evaluateMarketOrder(
                commandWithMarginMode(TradingMarginMode.CROSS),
                account("10000", TradingMarginMode.CROSS),
                freshQuote()
        );

        Assertions.assertTrue(decision.accepted(), "CROSS + cross_mode.enabled=true 应放行开仓");
        Assertions.assertNull(decision.liquidationPrice(), "CROSS 仓 liquidationPrice 应为 null（账户级强平）");
        // IM 冻结同 ISOLATED：margin 仍为账户币 inAccount=100
        Assertions.assertEquals(0, decision.margin().compareTo(new BigDecimal("100")),
                "CROSS IM 仍冻结，margin=margin.inAccount");
        // tier mmRate/tierNo 仍冻结（与 ISOLATED 一致）
        Assertions.assertEquals(0, decision.mmRateAtOpen().compareTo(new BigDecimal("0.005000")),
                "CROSS 仍冻结 tier mmRate");
        Assertions.assertEquals(1, decision.tierNoAtOpen(), "CROSS 仍冻结 tierNo");
        // CROSS 不算单仓强平价 → 强平价计算器不应被调用
        Mockito.verify(liquidationPriceCalculator, Mockito.never()).calculate(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    /**
     * Task 2 回归：ISOLATED 开仓不受 CROSS gate 影响，liquidationPrice 正常计算（非 null）。
     */
    @Test
    void shouldStillComputeLiquidationPriceForIsolatedOpen() {
        DefaultTradingRiskService service = createService();
        stubAcceptPathExceptTier();
        stubDefaultTier();
        Mockito.when(liquidationPriceCalculator.calculate(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(new BigDecimal("30000"));

        TradingRiskDecision decision = service.evaluateMarketOrder(
                commandWithMarginMode(TradingMarginMode.ISOLATED),
                account("10000", TradingMarginMode.ISOLATED),
                freshQuote()
        );

        Assertions.assertTrue(decision.accepted());
        Assertions.assertNotNull(decision.liquidationPrice(), "ISOLATED 仓 liquidationPrice 应正常计算");
        Assertions.assertEquals(0, decision.liquidationPrice().compareTo(new BigDecimal("30000")));
        // ISOLATED 不读 CROSS 开关
        Mockito.verify(riskSwitchCache, Mockito.never()).isEnabled(Mockito.anyString(), Mockito.anyBoolean());
    }

    // ─── STAGE-14B Task 6 收口（C-1）：quoteCurrency 缺失（过渡期旧快照）拒单而非 NPE ─────

    @Test
    void shouldRejectWhenSpecQuoteCurrencyIsNullToAvoidTransitionalNpe() {
        DefaultTradingRiskService service = createService();
        // schedule + isOpenAllowed 通过；leverage 兜底
        Mockito.when(tradingScheduleSnapshotRepository.findBySymbol("BTCUSDT"))
                .thenReturn(Optional.of(Mockito.mock(TradingScheduleSnapshot.class)));
        Mockito.when(tradingScheduleService.isOpenAllowed(Mockito.eq("BTCUSDT"), Mockito.any()))
                .thenReturn(true);
        Mockito.when(properties.getMaxLeverage()).thenReturn(new BigDecimal("100"));
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.empty());
        Mockito.when(tradingRiskConfigRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        // Task 5 过渡期旧快照：spec 存在但 quoteCurrency 为 null（market 尚未重发布带币种字段的快照）
        Mockito.when(marketSymbolSpecRepository.findByPlatformSymbol("BTCUSDT"))
                .thenReturn(Optional.of(new com.falconx.market.contract.SymbolSpec(
                        "BTCUSDT",
                        100,
                        new BigDecimal("0.0005"),
                        BigDecimal.ZERO,
                        new BigDecimal("0.00000001"),
                        new BigDecimal("1000000"),
                        BigDecimal.ZERO,
                        8,
                        8,
                        "BTC",
                        null,
                        1
                )));

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        // 拒单而非崩溃，复用既有 SYMBOL_SPEC_NOT_FOUND reason
        Assertions.assertFalse(decision.accepted());
        Assertions.assertEquals("SYMBOL_SPEC_NOT_FOUND", decision.rejectReason());
        // 不得进入 calculator 的 FX 路径（calculateInitialMargin 在 null 防守之后，绝不应被调用）
        Mockito.verify(marginCalculator, Mockito.never()).calculateInitialMargin(
                Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.verifyNoInteractions(fxRateService);
    }

    // ─── STAGE-14B Task 8 收口：margin FX 不可用拒单 ─────────────────────────

    /**
     * Task 8 收口用例：异币种 margin 的 FX 路径不可用（calculateInitialMargin 返回
     * inAccount=null / fxRate=null，inQuote 仍给出）→ 决策拒单、reason=FX_RATE_UNAVAILABLE
     * （master §7.3 错误码 30073），且不进入 fee 计算。
     */
    @Test
    void shouldRejectWhenMarginFxRateUnavailable() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecks();
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.empty());
        Mockito.when(properties.getMaxLeverage()).thenReturn(new BigDecimal("100"));
        Mockito.when(tradingRiskConfigRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        // FX 不可用：inAccount=null、fxRate=null（inQuote 仍给出），由调用方检测 inAccount==null 拒单。
        Mockito.when(marginCalculator.calculateInitialMargin(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("100"), null, null));

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertFalse(decision.accepted());
        Assertions.assertEquals("FX_RATE_UNAVAILABLE", decision.rejectReason());
        // margin FX 不可用应在 fee 计算之前拒单
        Mockito.verify(marginCalculator, Mockito.never()).calculateFee(
                Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any(), Mockito.any());
    }

    // ─── STAGE-14B Task 8：异币种 fee 换算收口混币（totalRequired 用账户币 fee）─────

    /**
     * Task 8 收口用例：非 USD-quote 品种 totalRequired 必须用账户币 fee（margin.inAccount + fee.inAccount），
     * 不再把原币 fee 直接相加。
     *
     * <p>构造：margin.inAccount=100、fee 原币 inQuote=20 但换算后账户币 inAccount=10、fx=0.5。
     * 正确 totalRequired = 100 + 10 = 110（账户币）。若仍按旧混币口径用原币 fee（20）则 total=120。
     * 账户可用余额设 115：
     * <ul>
     *   <li>账户币口径（110）→ 115 ≥ 110 → 通过（断言 accepted=true 证明用了 fee.inAccount）。</li>
     *   <li>旧混币口径（120）→ 115 &lt; 120 → 会拒单（反证）。</li>
     * </ul>
     * 同时断言 decision.fee()==10（账户币），与下游 chargeFee 扣账户币 balance 一致。
     */
    @Test
    void shouldUseAccountCurrencyFeeInTotalRequiredForNonUsdQuoteSymbol() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecks();
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.empty());
        Mockito.when(properties.getMaxLeverage()).thenReturn(new BigDecimal("100"));
        Mockito.when(tradingRiskConfigRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        // margin 账户币口径 inAccount=100、fx=0.5
        Mockito.when(marginCalculator.calculateInitialMargin(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("200"), new BigDecimal("100"), new BigDecimal("0.5")));
        // fee 原币 inQuote=20、账户币 inAccount=10、fx=0.5
        Mockito.when(marginCalculator.calculateFee(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("20"), new BigDecimal("10"), new BigDecimal("0.5")));
        Mockito.when(liquidationPriceCalculator.calculate(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(new BigDecimal("30000"));
        Mockito.when(properties.getMaintenanceMarginRate()).thenReturn(new BigDecimal("0.005"));
        stubDefaultTier();

        // 余额 115：账户币口径 total=110 应通过；旧混币口径 total=120 会被拒。
        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("115"), freshQuote());

        Assertions.assertTrue(decision.accepted(), "账户币口径 total=110 ≤ 115 应通过（证明 totalRequired 用 fee.inAccount）");
        Assertions.assertEquals(0, decision.fee().compareTo(new BigDecimal("10")),
                "decision.fee 应为账户币 fee.inAccount=10（与下游 chargeFee 扣账户币一致）");
    }

    /**
     * Task 8 收口用例（反证）：同上构造但余额仅 112。账户币口径 total=110 ≤ 112 仍应通过；
     * 若实现错误地用原币 fee（total=120）则 112 &lt; 120 会拒单。断言通过即证明未用原币 fee。
     */
    @Test
    void shouldNotRejectUsingRawQuoteFeeWhenAccountCurrencyTotalFits() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecks();
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.empty());
        Mockito.when(properties.getMaxLeverage()).thenReturn(new BigDecimal("100"));
        Mockito.when(tradingRiskConfigRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        Mockito.when(marginCalculator.calculateInitialMargin(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("200"), new BigDecimal("100"), new BigDecimal("0.5")));
        Mockito.when(marginCalculator.calculateFee(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("20"), new BigDecimal("10"), new BigDecimal("0.5")));
        Mockito.when(liquidationPriceCalculator.calculate(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(new BigDecimal("30000"));
        Mockito.when(properties.getMaintenanceMarginRate()).thenReturn(new BigDecimal("0.005"));
        stubDefaultTier();

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("112"), freshQuote());

        Assertions.assertTrue(decision.accepted(), "账户币口径 total=110 ≤ 112 应通过（旧混币口径 120 会误拒）");
    }

    // ─── STAGE-14B Task 9a：决策携带 fxRate / quoteCurrency / 原币值（开仓落账三列真值依据）─────

    /**
     * Task 9a：异币种（fx≠1）accept 决策必须携带 fxRate、quoteCurrency 与 margin/fee 的原币值，
     * 供应用层把开仓 margin/fee 落账三列写成真值（amount=inAccount、original=inQuote、currency=QC、fx=fxRate）。
     *
     * <p>构造非 USDT-quote 品种：margin inQuote=200 / inAccount=100 / fx=0.5；
     * fee inQuote=20 / inAccount=10 / fx=0.5；quoteCurrency=AUD（来自 stubPassthroughChecks 的 spec）。
     * 这里改 spec 让 quoteCurrency=AUD 以验证决策回填的是 spec.quoteCurrency()。
     */
    @Test
    void acceptedDecisionCarriesFxRateQuoteCurrencyAndOriginalAmounts() {
        DefaultTradingRiskService service = createService();
        // 用 AUD-quote spec（异币种）覆盖 stubPassthroughChecks 默认 USDT spec
        Mockito.when(tradingScheduleSnapshotRepository.findBySymbol("BTCUSDT"))
                .thenReturn(Optional.of(Mockito.mock(TradingScheduleSnapshot.class)));
        Mockito.when(tradingScheduleService.isOpenAllowed(Mockito.eq("BTCUSDT"), Mockito.any()))
                .thenReturn(true);
        Mockito.when(properties.getMaxLeverage()).thenReturn(new BigDecimal("100"));
        Mockito.when(marketSymbolSpecRepository.findByPlatformSymbol("BTCUSDT"))
                .thenReturn(Optional.of(new com.falconx.market.contract.SymbolSpec(
                        "BTCUSDT", 100, new BigDecimal("0.0005"), BigDecimal.ZERO,
                        new BigDecimal("0.00000001"), new BigDecimal("1000000"), BigDecimal.ZERO,
                        8, 8, "BTC", "AUD", 1)));
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.empty());
        Mockito.when(tradingRiskConfigRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        Mockito.when(marginCalculator.calculateInitialMargin(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("200"), new BigDecimal("100"), new BigDecimal("0.5")));
        Mockito.when(marginCalculator.calculateFee(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("20"), new BigDecimal("10"), new BigDecimal("0.5")));
        Mockito.when(liquidationPriceCalculator.calculate(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(new BigDecimal("30000"));
        Mockito.when(properties.getMaintenanceMarginRate()).thenReturn(new BigDecimal("0.005"));
        stubDefaultTier();

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertTrue(decision.accepted());
        Assertions.assertEquals(0, decision.fxRate().compareTo(new BigDecimal("0.5")), "fxRate 应为 margin fx(QC→AC)");
        Assertions.assertEquals(0, decision.feeFxRate().compareTo(new BigDecimal("0.5")), "feeFxRate 应为 fee fx(QC→AC)");
        Assertions.assertEquals("AUD", decision.quoteCurrency(), "quoteCurrency 应回填 spec.quoteCurrency");
        Assertions.assertEquals(0, decision.marginInQuote().compareTo(new BigDecimal("200")), "marginInQuote 应为原币 IM(QC)");
        Assertions.assertEquals(0, decision.feeInQuote().compareTo(new BigDecimal("20")), "feeInQuote 应为原币 Fee(QC)");
        // 既有账户币口径不变
        Assertions.assertEquals(0, decision.margin().compareTo(new BigDecimal("100")), "margin 仍为账户币 inAccount");
        Assertions.assertEquals(0, decision.fee().compareTo(new BigDecimal("10")), "fee 仍为账户币 inAccount");
    }

    /**
     * Task 9a：同币种（USDT-quote，fx=1）accept 决策三列退化 —— fxRate=1、quoteCurrency=USDT、
     * 原币值==账户币值。
     */
    @Test
    void acceptedDecisionSameCurrencyDegradesToFxOne() {
        DefaultTradingRiskService service = createService();
        stubPassthroughChecks();
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.empty());
        Mockito.when(properties.getMaxLeverage()).thenReturn(new BigDecimal("100"));
        Mockito.when(tradingRiskConfigRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        Mockito.when(marginCalculator.calculateInitialMargin(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ONE));
        Mockito.when(marginCalculator.calculateFee(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("1"), new BigDecimal("1"), BigDecimal.ONE));
        Mockito.when(properties.getDefaultFeeRate()).thenReturn(new BigDecimal("0.0005"));
        Mockito.when(liquidationPriceCalculator.calculate(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(new BigDecimal("30000"));
        Mockito.when(properties.getMaintenanceMarginRate()).thenReturn(new BigDecimal("0.005"));
        stubDefaultTier();

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertTrue(decision.accepted());
        Assertions.assertEquals(0, decision.fxRate().compareTo(BigDecimal.ONE));
        Assertions.assertEquals(0, decision.feeFxRate().compareTo(BigDecimal.ONE));
        Assertions.assertEquals("USDT", decision.quoteCurrency());
        Assertions.assertEquals(0, decision.marginInQuote().compareTo(decision.margin()));
        Assertions.assertEquals(0, decision.feeInQuote().compareTo(decision.fee()));
    }

    /**
     * STAGE-14B Task 9a 收口：margin 与 fee 各自查询一次 FX 快照，两次之间快照被刷新取到不同值。
     * 验证 accept 决策的 {@code fxRate} 来自 margin（calculateInitialMargin 返回的 MarginResult.fxRate），
     * {@code feeFxRate} 来自 fee（calculateFee 返回的 MarginResult.fxRate），且各自原币×fx==账户币自洽：
     * margin 100(QC)×0.5=50(AC)；fee 20(QC)×0.55=11(AC)。
     */
    @Test
    void acceptedDecisionCarriesDistinctMarginAndFeeFxRates() {
        DefaultTradingRiskService service = createService();
        Mockito.when(tradingScheduleSnapshotRepository.findBySymbol("BTCUSDT"))
                .thenReturn(Optional.of(Mockito.mock(TradingScheduleSnapshot.class)));
        Mockito.when(tradingScheduleService.isOpenAllowed(Mockito.eq("BTCUSDT"), Mockito.any()))
                .thenReturn(true);
        Mockito.when(properties.getMaxLeverage()).thenReturn(new BigDecimal("100"));
        Mockito.when(marketSymbolSpecRepository.findByPlatformSymbol("BTCUSDT"))
                .thenReturn(Optional.of(new com.falconx.market.contract.SymbolSpec(
                        "BTCUSDT", 100, new BigDecimal("0.0005"), BigDecimal.ZERO,
                        new BigDecimal("0.00000001"), new BigDecimal("1000000"), BigDecimal.ZERO,
                        8, 8, "BTC", "AUD", 1)));
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.empty());
        Mockito.when(tradingRiskConfigRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        // margin 查询时刻 FX=0.5：inQuote=100 → inAccount=50
        Mockito.when(marginCalculator.calculateInitialMargin(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("0.5")));
        // fee 查询时刻 FX 快照被刷新为 0.55：inQuote=20 → inAccount=11
        Mockito.when(marginCalculator.calculateFee(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("20"), new BigDecimal("11"), new BigDecimal("0.55")));
        Mockito.when(liquidationPriceCalculator.calculate(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(new BigDecimal("30000"));
        Mockito.when(properties.getMaintenanceMarginRate()).thenReturn(new BigDecimal("0.005"));
        stubDefaultTier();

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertTrue(decision.accepted());
        // margin 账携带 margin 查询时刻 fx
        Assertions.assertEquals(0, decision.fxRate().compareTo(new BigDecimal("0.5")), "fxRate 应来自 margin");
        // fee 账携带 fee 查询时刻 fx（与 margin 不同）
        Assertions.assertEquals(0, decision.feeFxRate().compareTo(new BigDecimal("0.55")), "feeFxRate 应来自 fee");
        Assertions.assertNotEquals(0, decision.fxRate().compareTo(decision.feeFxRate()),
                "margin 与 fee 的 fxRate 应不同");
        // margin 账三列自洽：原币×fx==账户币 → 100×0.5==50
        Assertions.assertEquals(0, decision.marginInQuote().multiply(decision.fxRate())
                .compareTo(decision.margin()), "margin 三列 original×fx 应等于 amount");
        // fee 账三列自洽：原币×fx==账户币 → 20×0.55==11
        Assertions.assertEquals(0, decision.feeInQuote().multiply(decision.feeFxRate())
                .compareTo(decision.fee()), "fee 三列 original×fx 应等于 amount");
    }

    /**
     * Task 9a：reject 决策新字段安全为 null（不进落账路径）。
     */
    @Test
    void rejectedDecisionHasNullCurrencyFields() {
        DefaultTradingRiskService service = createService();
        Mockito.when(tradingScheduleSnapshotRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertFalse(decision.accepted());
        Assertions.assertEquals("SYMBOL_NOT_SUPPORTED", decision.rejectReason());
        Assertions.assertNull(decision.fxRate());
        Assertions.assertNull(decision.feeFxRate());
        Assertions.assertNull(decision.quoteCurrency());
        Assertions.assertNull(decision.marginInQuote());
        Assertions.assertNull(decision.feeInQuote());
    }

    // ─── STAGE-14C1 Task 6：开仓接 LeverageTierResolver（tier 校验 + mmRate 用 tier + 冻结）─────

    /**
     * 公共桩：把 accept 路径所有非 tier 依赖打通（USDT-quote spec、fx=1、风控空、阈值空），
     * tier 桩由各 Task 6 用例自行控制（命中 / empty / 不同 mmRate），以便独立验证 tier 分支。
     */
    private void stubAcceptPathExceptTier() {
        stubPassthroughChecks();
        Mockito.when(tradingRiskControlActionRepository.hasActiveGlobalPause()).thenReturn(false);
        Mockito.when(tradingRiskControlActionRepository.findMostSevereActiveBySymbol("BTCUSDT"))
                .thenReturn(Optional.empty());
        Mockito.when(properties.getMaxLeverage()).thenReturn(new BigDecimal("100"));
        Mockito.when(tradingRiskConfigRepository.findBySymbol("BTCUSDT")).thenReturn(Optional.empty());
        // margin inQuote=100 / inAccount=100 / fx=1（USDT 账户、USDT 计价）→ notional(AC)=inAccount×lev=100×10=1000
        Mockito.when(marginCalculator.calculateInitialMargin(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ONE));
        Mockito.when(marginCalculator.calculateFee(Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new MarginResult(new BigDecimal("1"), new BigDecimal("1"), BigDecimal.ONE));
        Mockito.when(properties.getDefaultFeeRate()).thenReturn(new BigDecimal("0.0005"));
    }

    /**
     * Task 6：lev ≤ tier.maxLeverage 通过，且决策冻结 mmRateAtOpen=tier.mmRate / tierNoAtOpen=tier.tierNo。
     */
    @Test
    void shouldAcceptWhenLeverageWithinTierAndCarryFrozenTierValues() {
        DefaultTradingRiskService service = createService();
        stubAcceptPathExceptTier();
        // command lev=10 ≤ tier.maxLeverage=50；tier mmRate=0.004、tierNo=2
        Mockito.when(leverageTierResolver.resolve(Mockito.eq("BTCUSDT"), Mockito.any(), Mockito.anyString()))
                .thenReturn(Optional.of(new com.falconx.trading.service.model.LeverageTier(
                        2, 50, new BigDecimal("0.004000"), BigDecimal.ZERO, new BigDecimal("100000"))));
        // 强平价用 tier mmRate（断言下游收到的是 tier 值，而非 properties 的 0.005）
        Mockito.when(liquidationPriceCalculator.calculate(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.eq(new BigDecimal("0.004000")))).thenReturn(new BigDecimal("30000"));

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertTrue(decision.accepted());
        Assertions.assertEquals(0, decision.mmRateAtOpen().compareTo(new BigDecimal("0.004000")),
                "mmRateAtOpen 应冻结为 tier.mmRate");
        Assertions.assertEquals(2, decision.tierNoAtOpen(), "tierNoAtOpen 应冻结为 tier.tierNo");
        Assertions.assertEquals(0, decision.liquidationPrice().compareTo(new BigDecimal("30000")));
    }

    /**
     * Task 6：强平价改用 tier mmRate 替换硬码 —— 校验传入 LiquidationPriceCalculator 的第 5 参为 tier.mmRate。
     */
    @Test
    void shouldPassTierMmRateToLiquidationPriceCalculator() {
        DefaultTradingRiskService service = createService();
        stubAcceptPathExceptTier();
        Mockito.when(leverageTierResolver.resolve(Mockito.eq("BTCUSDT"), Mockito.any(), Mockito.anyString()))
                .thenReturn(Optional.of(new com.falconx.trading.service.model.LeverageTier(
                        3, 100, new BigDecimal("0.010000"), BigDecimal.ZERO, null)));
        Mockito.when(liquidationPriceCalculator.calculate(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(new BigDecimal("25000"));

        service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        // 强平价用 tier.mmRate=0.010000（不是 properties 的 0.005）；属性 mmRate 不应被读取
        Mockito.verify(liquidationPriceCalculator).calculate(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.eq(new BigDecimal("0.010000")));
        Mockito.verify(properties, Mockito.never()).getMaintenanceMarginRate();
    }

    /**
     * Task 6：lev &gt; tier.maxLeverage → reject LEVERAGE_EXCEEDS_TIER（30070）。
     * 构造：command lev=10，tier.maxLeverage=5（落高档，档位上限严于全局 100）。
     */
    @Test
    void shouldRejectWhenLeverageExceedsTier() {
        DefaultTradingRiskService service = createService();
        stubAcceptPathExceptTier();
        Mockito.when(leverageTierResolver.resolve(Mockito.eq("BTCUSDT"), Mockito.any(), Mockito.anyString()))
                .thenReturn(Optional.of(new com.falconx.trading.service.model.LeverageTier(
                        4, 5, new BigDecimal("0.020000"), new BigDecimal("100000"), null)));

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertFalse(decision.accepted());
        Assertions.assertEquals("LEVERAGE_EXCEEDS_TIER", decision.rejectReason());
        Assertions.assertNull(decision.mmRateAtOpen(), "reject 分支 mmRateAtOpen 应为 null");
        Assertions.assertNull(decision.tierNoAtOpen(), "reject 分支 tierNoAtOpen 应为 null");
        // 杠杆超档应在强平价计算前拒单
        Mockito.verify(liquidationPriceCalculator, Mockito.never()).calculate(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    /**
     * Task 6：tier 解析 empty（无配置 / 落不进任何档）→ reject TIER_CONFIG_NOT_FOUND（30072）。
     */
    @Test
    void shouldRejectWhenTierConfigNotFound() {
        DefaultTradingRiskService service = createService();
        stubAcceptPathExceptTier();
        Mockito.when(leverageTierResolver.resolve(Mockito.eq("BTCUSDT"), Mockito.any(), Mockito.anyString()))
                .thenReturn(Optional.empty());

        TradingRiskDecision decision = service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        Assertions.assertFalse(decision.accepted());
        Assertions.assertEquals("TIER_CONFIG_NOT_FOUND", decision.rejectReason());
        Assertions.assertNull(decision.mmRateAtOpen());
        Assertions.assertNull(decision.tierNoAtOpen());
    }

    /**
     * Task 6：tier 按账户币 notional 解析 —— 验证传入 resolver 的 notional(AC)=margin.inAccount×lev。
     * margin.inAccount=100、command lev=10 → notional(AC)=1000。
     */
    @Test
    void shouldResolveTierWithAccountCurrencyNotional() {
        DefaultTradingRiskService service = createService();
        stubAcceptPathExceptTier();
        Mockito.when(leverageTierResolver.resolve(Mockito.anyString(), Mockito.any(), Mockito.anyString()))
                .thenReturn(Optional.of(new com.falconx.trading.service.model.LeverageTier(
                        1, 100, new BigDecimal("0.005000"), BigDecimal.ZERO, null)));
        Mockito.when(liquidationPriceCalculator.calculate(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(new BigDecimal("30000"));

        service.evaluateMarketOrder(command(), account("10000"), freshQuote());

        // notional(AC) = margin.inAccount(100) × lev(10) = 1000，group_code 兜底 "default"
        Mockito.verify(leverageTierResolver).resolve(
                Mockito.eq("BTCUSDT"),
                Mockito.argThat(n -> n != null && n.compareTo(new BigDecimal("1000")) == 0),
                Mockito.eq("default"));
    }
}
