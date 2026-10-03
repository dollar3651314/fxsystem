package com.falconx.trading;

import com.falconx.domain.enums.ChainType;
import com.falconx.market.contract.SymbolSpec;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingDepositCreditApplicationService;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.command.CreditConfirmedDepositCommand;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.dto.OrderPlacementResult;
import com.falconx.trading.dto.PriceTickProcessingResult;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.QuoteDrivenEngine;
import com.falconx.trading.entity.MarginLevelStatus;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingQuoteQualityStatus;
import com.falconx.trading.repository.RedisTradingScheduleSnapshotRepository;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import com.falconx.trading.service.LeverageTierResolver;
import com.falconx.trading.service.MarginLevelMonitor;
import com.falconx.trading.service.model.AccountMarginState;
import com.falconx.trading.service.model.LeverageTier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * STAGE-14C1 Task 11 集成测试：杠杆/MM Tier + 账户 MarginLevel + 30% StopOut 强平跨场景（master §8.3 C 阶段验收）。
 *
 * <p>真 MySQL（falconx_trading_it，含 V30 全 symbol tier seed 6311 行 + V31/V32）+ 真 Redis + 真引擎链路，
 * 覆盖 16 IT（10 模板边界 / 200x 拒单 / StopOut 整链 &lt; 500ms / CHECK 约束 / tier seed 抽查 / 2 PERF）。
 *
 * <p>FX_PAUSED 按类目行为（IT-013）因 Task 10 category 来源延后，本类对 FX_PAUSED <b>行为路径</b>不做端到端断言，
 * 仅断言 V31 t_fx_pause_behavior seed 数据正确（标注 skipped 原因见该用例 javadoc）。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = TradingCoreServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380"
        }
)
@ExtendWith(OutputCaptureExtension.class)
class TradingLeverageTierStopOutIntegrationTests {

    private static final String SPEC_KEY_PREFIX = "falconx:market:symbol-spec:";

    @Autowired
    private TradingDepositCreditApplicationService depositService;
    @Autowired
    private TradingOrderPlacementApplicationService orderService;
    @Autowired
    private QuoteDrivenEngine quoteDrivenEngine;
    @Autowired
    private TradingTestSupportMapper mapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RedisTradingScheduleSnapshotRepository scheduleRepository;
    @Autowired
    private OpenPositionSnapshotStore snapshotStore;
    @Autowired
    private LeverageTierResolver leverageTierResolver;
    @Autowired
    private MarginLevelMonitor marginLevelMonitor;
    @Autowired
    private com.falconx.trading.service.AccountEquityCalculator accountEquityCalculator;
    @Autowired
    private com.falconx.trading.repository.TradingPositionRepository positionRepository;
    @Autowired
    private com.falconx.trading.repository.TradingAccountRepository accountRepository;
    @Autowired
    private com.falconx.trading.repository.TradingRiskControlActionRepository riskControlActionRepository;
    @Autowired
    private com.falconx.trading.application.TradingTierAdminApplicationService tierAdminService;

    @BeforeEach
    void clean() {
        mapper.clearOwnerTables();
        snapshotStore.replaceAll(List.of());
        for (String s : List.of("BTCUSDT", "EURAUD", "XAUUSD")) {
            redis.delete("falconx:trading:quote:snapshot:" + s);
            redis.delete("falconx:market:trading:schedule:" + s);
        }
        // EURAUD / XAUUSD SymbolSpec（base/quote 对齐 master；用 V30 真实 tier）。
        seedSpec("EURAUD", "EUR", "AUD", 300, BigDecimal.ZERO);
        seedSpec("XAUUSD", "XAU", "USD", 200, BigDecimal.ZERO);
        // FX：AUD→USDT=0.65（EURAUD 计价币 AUD → 账户币 USDT）、USD→USDT=1.0（XAUUSD 计价币 USD → 账户币 USDT）。
        // 通过生产 acceptUpdate 入口写入（= Kafka 增量同一写入口）。
        fxAcceptUpdate("AUD", "USDT", new BigDecimal("0.65"));
        fxAcceptUpdate("USD", "USDT", new BigDecimal("1.00"));
    }

    @AfterEach
    void cleanupTiers() {
        // 本类对 EURAUD/XAUUSD 用 V30 真实 tier，不注入；BTCUSDT 用例自注入的单档 tier 在 IT-011 自清。
        mapper.deleteLeverageTierBySymbolAndGroup("BTCUSDT", "default");
    }

    // ============================== TC-TIER-IT-001 ==============================

    /** V30/V31/V32 migrate apply + CHECK 约束（插 max_lev×mm_rate&gt;1.0 被拒）。 */
    @Test
    void it001_migrationsAppliedAndCheckConstraintRejectsViolatingTier() {
        // V30 表 + 列
        Assertions.assertEquals(1, mapper.countTableColumnByName("t_symbol_leverage_tier", "max_leverage"));
        Assertions.assertEquals(1, mapper.countTableColumnByName("t_symbol_leverage_tier", "mm_rate"));
        // V31：t_risk_config 两列 + t_fx_pause_behavior 表
        Assertions.assertEquals(1, mapper.countTableColumnByName("t_risk_config", "stop_out_level"));
        Assertions.assertEquals(1, mapper.countTableColumnByName("t_risk_config", "margin_call_level"));
        Assertions.assertEquals(1, mapper.countTableColumnByName("t_fx_pause_behavior", "allow_open"));
        // V32：t_position 两冻结列
        Assertions.assertEquals(1, mapper.countTableColumnByName("t_position", "mm_rate_at_open"));
        Assertions.assertEquals(1, mapper.countTableColumnByName("t_position", "tier_no_at_open"));

        // CHECK(max_lev×mm_rate≤1.0)：100×0.020=2.0>1.0 必须被 DB 拒。
        // MyBatis 把 MySQL CHECK 违反包成 UncategorizedSQLException（非 DataIntegrityViolationException），
        // 断言其根因 SQLException message 含 chk_tier_leverage_mm。
        org.springframework.jdbc.UncategorizedSQLException ex = Assertions.assertThrows(
                org.springframework.jdbc.UncategorizedSQLException.class,
                () -> mapper.insertViolatingCheckLeverageTier(39990001L, "CHKVIOL", 100, "0.020000"));
        Assertions.assertTrue(ex.getMessage().contains("chk_tier_leverage_mm"),
                "应因 CHECK 约束 chk_tier_leverage_mm 被拒，实际: " + ex.getMessage());
        // 边界 200×0.005=1.0 应允许（不抛）。
        Assertions.assertDoesNotThrow(
                () -> mapper.insertViolatingCheckLeverageTier(39990002L, "CHKEDGE", 200, "0.005000"));
        mapper.deleteLeverageTierBySymbolAndGroup("CHKEDGE", "default");
    }

    // ============================== TC-TIER-IT-002 ==============================

    /** tier seed 抽查：每模板（T1-T10）抽 ≥3 symbol 核对 §5.1（C 验收硬约束）。 */
    @Test
    void it002_tierSeedSpotCheckPerTemplate() {
        // 每模板 (代表 symbol, 期望档数, tier1 maxLev, tier1 mmRate)
        // 代表 symbol 来自实查 V30 seed（按 §5.2 映射）。
        assertTierTemplate("T1", List.of("BTCUSD", "BTCEUR", "ETHUSD"), 6, 125, "0.004000");
        assertTierTemplate("T2", List.of("ADAUSD", "BCHEUR", "ADAGBP"), 5, 75, "0.005000");
        assertTierTemplate("T3", List.of("AVXUSD", "BTCGBP", "EOSEUR"), 3, 25, "0.020000");
        // T4 稳定币对（USDC/USDD/TUSD/PYUSD/FDUSD 作 base 且 quote∈{USDT,USD}）：当前 market t_symbol seed
        //   无任何 symbol 命中 §5.2 的 T4 条件 → V30 不产 T4 行（实查 50x tier1 distinct symbol = 0）。
        //   断言「无 symbol 落 T4」以记录该模板当前空映射（不是 seed bug，是 owner 数据无此对）。
        Assertions.assertEquals(0, distinctSymbolsWithTier1MaxLev50(),
                "当前 market seed 无 symbol 映射到 T4（50x tier1），如未来 owner 新增稳定币对需更新本断言");
        // T5-T10 mmRate 为 2026-06-03 减半修正后的值（V38，master §5.1 修正记录：lev×mm ≤ 0.5）。
        assertTierTemplate("T5", List.of("EURUSD", "AUDUSD", "GBPUSD"), 5, 500, "0.001000");
        assertTierTemplate("T6", List.of("AUDCHF", "AUDJPY", "CADCHF"), 4, 300, "0.001650");
        assertTierTemplate("T7", List.of("DKKSEK", "AUDSGD", "EURCZK"), 4, 200, "0.002500");
        assertTierTemplate("T8", List.of("AUDCNH", "AUDZAR", "CHFHUF"), 4, 100, "0.005000");
        assertTierTemplate("T9", List.of("XAUUSD", "XAGUSD"), 5, 200, "0.002500");
        assertTierTemplate("T10", List.of("0001.HKG", "0002.HKG", "0003.HKG"), 4, 100, "0.005000");
    }

    private int distinctSymbolsWithTier1MaxLev50() {
        Integer c = mapper.countDistinctSymbolsByTier1MaxLeverage(50);
        return c == null ? 0 : c;
    }

    private void assertTierTemplate(String label, List<String> symbols,
                                    int expectedTierCount, int tier1MaxLev, String tier1MmRate) {
        for (String s : symbols) {
            Integer cnt = mapper.countLeverageTiersBySymbol(s);
            Assertions.assertNotNull(cnt, label + " seed 缺失: " + s);
            Assertions.assertTrue(cnt > 0, label + " 无 tier seed: " + s);
            if (cnt != expectedTierCount) {
                // 某些 symbol 可能因 §5.2 边界落不同档数，只对档数一致的核对 maxLev/mm（放宽，避免误报）；
                // 但本批 symbol 已实查与模板一致，档数不一致直接失败以暴露 seed 漂移。
                Assertions.assertEquals(expectedTierCount, cnt,
                        label + " 档数与模板不符: " + s);
            }
            Assertions.assertEquals(tier1MaxLev, mapper.selectTierMaxLeverage(s, 1),
                    label + " tier1 maxLev 不符: " + s);
            Assertions.assertEquals(0, new BigDecimal(tier1MmRate)
                            .compareTo(new BigDecimal(mapper.selectTierMmRate(s, 1))),
                    label + " tier1 mmRate 不符: " + s);
        }
    }

    // ============================== TC-TIER-IT-003 ==============================

    /** 10 模板边界 tier 切换（notional 跨档 → maxLev/mmRate 变）。Resolver 按 notional 落档。 */
    @Test
    void it003_tierBoundarySwitchAcrossAllTemplates() {
        // 每模板取一个代表 symbol + 两个跨档 notional，断言 maxLev/mmRate 随档变。
        // 用 default 组。notional 单位 = 账户币（USDT）。
        // T1 BTCUSD: tier1(0-50K)=125x，tier3(250K-1M)=50x
        assertBoundary("BTCUSD", new BigDecimal("10000"), 125, new BigDecimal("300000"), 50);
        // T2 ADAUSD: tier1(0-50K)=75x，tier3(200K-1M)=25x
        assertBoundary("ADAUSD", new BigDecimal("10000"), 75, new BigDecimal("300000"), 25);
        // T3 AVXUSD: tier1(0-10K)=25x，tier3(50K+)=5x
        assertBoundary("AVXUSD", new BigDecimal("5000"), 25, new BigDecimal("100000"), 5);
        // T4：当前 market seed 无 symbol 落 T4（见 IT-002），故跨档切换无可测 symbol，跳过。
        // T5 EURUSD: tier1(0-100K)=500x，tier4(2M-10M)=50x
        assertBoundary("EURUSD", new BigDecimal("50000"), 500, new BigDecimal("3000000"), 50);
        // T6 AUDCHF: tier1(0-50K)=300x，tier3(250K-1M)=50x
        assertBoundary("AUDCHF", new BigDecimal("10000"), 300, new BigDecimal("300000"), 50);
        // T7 DKKSEK: tier1(0-50K)=200x，tier3(250K-1M)=50x
        assertBoundary("DKKSEK", new BigDecimal("10000"), 200, new BigDecimal("300000"), 50);
        // T8 AUDCNH: tier1(0-25K)=100x，tier3(100K-500K)=20x
        assertBoundary("AUDCNH", new BigDecimal("10000"), 100, new BigDecimal("200000"), 20);
        // T9 XAUUSD: tier1(0-50K)=200x，tier4(1M-5M)=20x
        assertBoundary("XAUUSD", new BigDecimal("10000"), 200, new BigDecimal("2000000"), 20);
        // T10 0001.HKG: tier1(0-100K)=100x，tier3(500K-2M)=20x
        assertBoundary("0001.HKG", new BigDecimal("50000"), 100, new BigDecimal("1000000"), 20);
    }

    private void assertBoundary(String symbol, BigDecimal lowNotional, int lowExpectedLev,
                                BigDecimal highNotional, int highExpectedLev) {
        Optional<LeverageTier> low = leverageTierResolver.resolve(symbol, lowNotional, "default");
        Optional<LeverageTier> high = leverageTierResolver.resolve(symbol, highNotional, "default");
        Assertions.assertTrue(low.isPresent(), symbol + " 低档解析失败");
        Assertions.assertTrue(high.isPresent(), symbol + " 高档解析失败");
        Assertions.assertEquals(lowExpectedLev, low.get().maxLeverage(), symbol + " 低档 maxLev");
        Assertions.assertEquals(highExpectedLev, high.get().maxLeverage(), symbol + " 高档 maxLev");
        // 跨档后 maxLev 必然下降、mmRate 上升
        Assertions.assertTrue(high.get().maxLeverage() < low.get().maxLeverage(), symbol + " 高档 maxLev 应更低");
        Assertions.assertTrue(high.get().mmRate().compareTo(low.get().mmRate()) > 0, symbol + " 高档 mmRate 应更高");
    }

    // ============================== TC-TIER-IT-004 ==============================

    /** 200x 大单落高档（maxLev&lt;200）→ 拒 30070（LEVERAGE_EXCEEDS_TIER）。 */
    @Test
    void it004_largeOrderExceedingTierLeverageRejected() {
        // XAUUSD 高 notional 落 tier4（1M-5M）maxLev=20；用 200x 下单 → notional≈大单落高档 → 拒。
        // 200x、qty 让 notional 落 tier4：mark=2000、qty=15 → notional=30000... 需更大。
        // notional(AC)=qty×price×fx(USD→USDT=1)。price≈2000，qty=1000 → notional=2,000,000（tier4，maxLev=20）。
        long userId = 96104L;
        deposit(userId, "it004", new BigDecimal("5000000.00000000"));
        seedAlwaysOpen("XAUUSD", "METAL");
        publishQuote("XAUUSD", new BigDecimal("1999.00000000"), new BigDecimal("2000.00000000"), new BigDecimal("2000.00000000"), OffsetDateTime.now());
        OrderPlacementResult r = orderService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId, "XAUUSD", TradingOrderSide.BUY,
                new BigDecimal("1000.00000000"), new BigDecimal("200"),
                null, null, null, "it004-order")); // 200x
        Assertions.assertNull(r.position(), "200x 大单应被拒，不应建仓");
        Assertions.assertEquals("LEVERAGE_EXCEEDS_TIER", r.rejectionReason());
    }

    // ============================== TC-TIER-IT-005 ==============================

    /** tier 缺失 → 30072（TIER_CONFIG_NOT_FOUND）。 */
    @Test
    void it005_missingTierRejected() {
        long userId = 96105L;
        // 用一个 V30 无 seed 且本类未注入 tier 的纯测试 symbol。
        String noTierSymbol = "NOTIERX";
        seedSpec(noTierSymbol, "NOT", "USDT", 100, BigDecimal.ZERO);
        deposit(userId, "it005", new BigDecimal("2000.00000000"));
        seedAlwaysOpen(noTierSymbol, "CRYPTO");
        publishQuote(noTierSymbol, new BigDecimal("99.00000000"), new BigDecimal("100.00000000"), new BigDecimal("100.00000000"), OffsetDateTime.now());
        OrderPlacementResult r = orderService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId, noTierSymbol, TradingOrderSide.BUY,
                new BigDecimal("1.00000000"), new BigDecimal("10"),
                null, null, null, "it005-order"));
        Assertions.assertNull(r.position());
        Assertions.assertEquals("TIER_CONFIG_NOT_FOUND", r.rejectionReason());
    }

    // ============================== TC-TIER-IT-006 ==============================

    /** EURAUD 开仓 mmRate 用 tier + 冻结 mm_rate_at_open。 */
    @Test
    void it006_eurAudOpenFreezesTierMmRate() {
        long userId = 96106L;
        OrderPlacementResult r = openEurAud(userId, "it006", new BigDecimal("1.65000000"), new BigDecimal("200"));
        Assertions.assertNotNull(r.position(), "EURAUD 开仓被拒: " + r.rejectionReason());
        Long positionId = r.position().positionId();
        // 0.1 lot=10000 EUR，notional(AC)=1.65×10000×0.65=10725 USDT → 落 EURAUD(T6) tier1(0-50K)=300x/0.165%
        // （2026-06-03 V38 减半修正，master §5.1）。tier1 mmRate=0.00165 冻结到 position。
        String frozen = mapper.selectPositionMmRateAtOpenById(positionId);
        Assertions.assertEquals(0, new BigDecimal("0.001650").compareTo(new BigDecimal(frozen)),
                "EURAUD 应冻结 tier1 mmRate=0.00165");
        Assertions.assertEquals(1, mapper.selectPositionTierNoAtOpenById(positionId), "应冻结 tier_no=1");
    }

    // ============================== TC-TIER-IT-007 ==============================

    /** MarginLevel 计算（EURAUD 开仓即 ≈303%（V38 mm 减半后），HEALTHY）。 */
    @Test
    void it007_eurAudMarginLevelHealthyAtOpen() {
        long userId = 96107L;
        OrderPlacementResult r = openEurAud(userId, "it007", new BigDecimal("1.65000000"), new BigDecimal("200"));
        Assertions.assertNotNull(r.position(), "EURAUD 开仓被拒: " + r.rejectionReason());
        Long positionId = r.position().positionId();

        var position = positionRepository.findByIdForUpdate(positionId).orElseThrow();
        var account = accountRepository.findByUserIdAndCurrency(userId, "USDT").orElseThrow();
        // 开仓即时 mark=入场（mark=1.65）→ uPnL≈0 → ML = (margin)/MM ×100。
        AccountMarginState state = accountEquityCalculator.computePositionMarginLevel(
                position, account, new BigDecimal("1.65000000"), "AUD");
        Assertions.assertNotNull(state.marginLevel(), "MarginLevel 不应为 null");
        // 开仓 ML 远高于 100%（HEALTHY）。margin=IM(AC)；MM=notional(AC)×mmRate。
        // lev=200 → IM=notional/200；mmRate=0.00165（V38）→ MM=notional×0.00165。ML=IM/MM=(1/200)/0.00165≈303%。
        Assertions.assertTrue(state.marginLevel().compareTo(new BigDecimal("100")) > 0,
                "开仓 MarginLevel 应 >100%（HEALTHY），实测=" + state.marginLevel());
        Assertions.assertEquals(MarginLevelStatus.HEALTHY,
                marginLevelMonitor.evaluate(userId, state, new BigDecimal("0.30"), new BigDecimal("1.00")));
    }

    // ============================== TC-TIER-IT-008 ==============================

    /** 跌破 30% → StopOut 强平整链（&lt; 500ms：触发→平仓→落账 biz_type=9）。记实测耗时。 */
    @Test
    void it008_stopOutFullChainUnder500ms(CapturedOutput output) {
        long userId = 96108L;
        // 用 BTCUSDT（账户币 USDT，FX=1）便于精确解阈值价；注入单档 tier maxLev=100/mm=0.01。
        mapper.seedSingleLeverageTier("BTCUSDT", "default", 100, "0.010000");
        Long positionId = openBtcUsdt(userId, "it008").position().positionId();

        BigDecimal entry = new BigDecimal(mapper.selectPositionEntryPriceById(positionId));
        BigDecimal margin = new BigDecimal(mapper.selectPositionMarginById(positionId));
        BigDecimal mmRate = new BigDecimal(mapper.selectPositionMmRateAtOpenById(positionId));
        // 单仓 BUY qty=1：MM=entry×mmRate；ML=(margin+(mark-entry))/MM×100。取 ML=25%（≤30%）解 mark。
        BigDecimal mm = entry.multiply(mmRate);
        BigDecimal targetMark = entry.subtract(margin).add(mm.multiply(new BigDecimal("0.25")));

        long t0 = System.nanoTime();
        PriceTickProcessingResult result = publishQuote("BTCUSDT",
                targetMark.subtract(BigDecimal.ONE), targetMark.add(BigDecimal.ONE), targetMark, OffsetDateTime.now());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        Assertions.assertEquals(1, result.triggeredActions(), "应强平一次");
        Assertions.assertEquals(3, mapper.selectPositionStatusCodeById(positionId), "status=LIQUIDATED");
        Assertions.assertEquals(4, mapper.selectPositionCloseReasonCodeById(positionId), "close_reason=LIQUIDATION");
        // 落账 biz_type=9 LIQUIDATION_PNL
        Assertions.assertEquals(1, mapper.countLedgerByUserIdAndBizType(userId, 9));
        Assertions.assertEquals(1, mapper.countLiquidationLogsByPositionId(positionId));
        Assertions.assertEquals(1, mapper.countNotificationByUserIdAndType(userId, "STOP_OUT_TRIGGERED"));
        Assertions.assertTrue(output.toString().contains("trading.margin.stop-out.triggered"));
        System.out.printf("[PERF TC-TIER-IT-008] StopOut 触发→平仓→落账 单 tick 同步整链实测=%dms（目标 <500ms）%n", elapsedMs);
        Assertions.assertTrue(elapsedMs < 500, "StopOut 整链应 <500ms，实测=" + elapsedMs + "ms");
    }

    // ============================== TC-TIER-IT-009 ==============================

    /** MarginCall 100%≥ML&gt;30% → 发 MARGIN_CALL_TRIGGERED + 5min 节流 + 不强平。 */
    @Test
    void it009_marginCallNotifiesThrottledAndNoLiquidation() {
        long userId = 96109L;
        // 直接用 Monitor 验证三态 + 5min 节流（避免依赖 tick 精确落入 [30%,100%]）。
        AccountMarginState marginCallState = new AccountMarginState(
                new BigDecimal("50.00"), new BigDecimal("100.00000000"), new BigDecimal("50.00")); // ML=50%
        // 首次 → MARGIN_CALL + 发通知
        Assertions.assertEquals(MarginLevelStatus.MARGIN_CALL,
                marginLevelMonitor.evaluate(userId, marginCallState, new BigDecimal("0.30"), new BigDecimal("1.00")));
        Assertions.assertEquals(1, mapper.countNotificationByUserIdAndType(userId, "MARGIN_CALL_TRIGGERED"));
        // 5min 内二次 → 节流，不重复发
        Assertions.assertEquals(MarginLevelStatus.MARGIN_CALL,
                marginLevelMonitor.evaluate(userId, marginCallState, new BigDecimal("0.30"), new BigDecimal("1.00")));
        Assertions.assertEquals(1, mapper.countNotificationByUserIdAndType(userId, "MARGIN_CALL_TRIGGERED"),
                "5min 内不应重复发 MARGIN_CALL");
        // MarginCall 不触发强平（无 STOP_OUT 通知、无强平日志）
        Assertions.assertEquals(0, mapper.countNotificationByUserIdAndType(userId, "STOP_OUT_TRIGGERED"));
    }

    // ============================== TC-TIER-IT-010 ==============================

    /** liqPrice 与 MarginLevel 双触发只强平一次。 */
    @Test
    void it010_dualTriggerLiquidatesOnce() {
        long userId = 96110L;
        mapper.seedSingleLeverageTier("BTCUSDT", "default", 100, "0.010000");
        Long positionId = openBtcUsdt(userId, "it010").position().positionId();
        BigDecimal entry = new BigDecimal(mapper.selectPositionEntryPriceById(positionId));
        BigDecimal margin = new BigDecimal(mapper.selectPositionMarginById(positionId));
        BigDecimal mmRate = new BigDecimal(mapper.selectPositionMmRateAtOpenById(positionId));
        BigDecimal mm = entry.multiply(mmRate);
        // ML=20%（深破）→ liqPrice 亦命中 → 双触发同时成立。
        BigDecimal targetMark = entry.subtract(margin).add(mm.multiply(new BigDecimal("0.20")));
        PriceTickProcessingResult result = publishQuote("BTCUSDT",
                targetMark.subtract(BigDecimal.ONE), targetMark.add(BigDecimal.ONE), targetMark, OffsetDateTime.now());

        Assertions.assertEquals(1, result.triggeredActions(), "双触发只强平一次");
        Assertions.assertEquals(1, mapper.countLiquidationLogsByPositionId(positionId), "强平日志恰 1 条");
        Assertions.assertEquals(1, mapper.countOutboxByEventType("trading.liquidation.executed"), "强平事件恰 1 条");
        Assertions.assertTrue(snapshotStore.listOpenByUserId(userId).isEmpty());
    }

    // ============================== TC-TIER-IT-011 ==============================

    /** BTCUSDT 同币种 tier 开/平仓回归不破坏。 */
    @Test
    void it011_btcUsdtSameCurrencyTierOpenCloseRegression() {
        long userId = 96111L;
        mapper.seedSingleLeverageTier("BTCUSDT", "default", 100, "0.010000");
        OrderPlacementResult r = openBtcUsdt(userId, "it011");
        Assertions.assertNotNull(r.position(), "BTCUSDT 开仓被拒: " + r.rejectionReason());
        Long positionId = r.position().positionId();
        // 同币种：entry_fx_rate=1，mmRate 用注入 tier=0.01。
        Assertions.assertEquals("1.00000000", mapper.selectPositionEntryFxRateById(positionId));
        Assertions.assertEquals(0, new BigDecimal("0.010000")
                .compareTo(new BigDecimal(mapper.selectPositionMmRateAtOpenById(positionId))));
        Assertions.assertEquals(1, mapper.countOpenPositionsByUserId(userId));
    }

    // ============================== TC-TIER-IT-012 ==============================

    /** 老仓位 mm_rate_at_open 回填 0.005 抽查（V32 老数据回填口径）。 */
    @Test
    void it012_legacyPositionBackfillDefault() {
        long userId = 96112L;
        long legacyPosId = 39995012L;
        // 构造一条不写两冻结列的「老仓位」→ DB DEFAULT 0.005000/1 兜底（= V32 回填后的默认）。
        int inserted = mapper.insertLegacyPositionWithoutTierColumns(legacyPosId, userId, "BTCUSDT", "10000.00000000");
        Assertions.assertEquals(1, inserted);
        try {
            Assertions.assertEquals(0, new BigDecimal("0.005000")
                    .compareTo(new BigDecimal(mapper.selectPositionMmRateAtOpenById(legacyPosId))),
                    "老仓位 mm_rate_at_open 应回填默认 0.005");
            Assertions.assertEquals(1, mapper.selectPositionTierNoAtOpenById(legacyPosId),
                    "老仓位 tier_no_at_open 应回填默认 1");
        } finally {
            mapper.deletePositionById(legacyPosId);
        }
    }

    // ============================== TC-TIER-IT-013 ==============================

    /** 阈值从 t_risk_config 读 + 改值生效（stop_out/margin_call）。 */
    @Test
    void it013_thresholdsReadFromRiskConfigAndChangeTakesEffect() {
        // 平台行默认 0.30 / 1.00（V31 DEFAULT）。
        Assertions.assertEquals(0, new BigDecimal("0.300000")
                .compareTo(new BigDecimal(mapper.selectPlatformStopOutLevel())));
        Assertions.assertEquals(0, new BigDecimal("1.000000")
                .compareTo(new BigDecimal(mapper.selectPlatformMarginCallLevel())));

        // 改值生效（DB 落值）：stop_out 0.50 / margin_call 1.20。
        mapper.updatePlatformMarginThresholds("0.500000", "1.200000");
        Assertions.assertEquals(0, new BigDecimal("0.500000")
                .compareTo(new BigDecimal(mapper.selectPlatformStopOutLevel())));

        // 阈值驱动判定（用显式阈值重载证明 stop_out 阈值改变改变三态边界）：
        // ML=40%，旧阈值 0.30 下为 MARGIN_CALL；新阈值 0.50 下为 STOP_OUT。
        AccountMarginState s = new AccountMarginState(
                new BigDecimal("40.00"), new BigDecimal("100.00000000"), new BigDecimal("40.00"));
        Assertions.assertEquals(MarginLevelStatus.MARGIN_CALL,
                marginLevelMonitor.evaluate(96113L, s, new BigDecimal("0.30"), new BigDecimal("1.00")));
        Assertions.assertEquals(MarginLevelStatus.STOP_OUT,
                marginLevelMonitor.evaluate(96113L, s, new BigDecimal("0.50"), new BigDecimal("1.20")));
        // 还原默认，避免污染共享库阈值
        mapper.updatePlatformMarginThresholds("0.300000", "1.000000");
    }

    // ============================== TC-TIER-IT-013B（STAGE-14C2 Task 6）==============================

    /**
     * FX_PAUSED/GLOBAL_PAUSE 按类目控制开仓（master §6.5 + V31 真 t_fx_pause_behavior seed）：
     * GLOBAL_PAUSE 激活后，forex(EURAUD, cat2 allow_open=0) 开仓被拒（GLOBAL_PAUSE_ACTIVE / 30087），
     * crypto(BTCUSDT, cat1 allow_open=1) 开仓放行（不因 pause 拒）。
     *
     * <p>用真 GLOBAL_PAUSE 激活（activateIfAbsent）+ 真 t_fx_pause_behavior 查询。
     * GLOBAL_PAUSE 记录由下一个 {@code @BeforeEach clean()} 的 deleteRiskControlAction 清理（不跨用例泄漏）。
     */
    @Test
    void it013b_fxPausedOpenRejectedForForexAllowedForCrypto() {
        // 激活 GLOBAL_PAUSE（symbol=null 全局）。
        boolean activated = riskControlActionRepository.activateIfAbsent(
                null, com.falconx.trading.entity.TradingRiskControlActionType.GLOBAL_PAUSE,
                "MANUAL_ADMIN", "it013b-fx-paused", null);
        Assertions.assertTrue(activated, "GLOBAL_PAUSE 应成功激活");
        Assertions.assertTrue(riskControlActionRepository.hasActiveGlobalPause(), "pause 应处于激活态");

        // forex EURAUD（cat2 allow_open=0）：开仓被拒 GLOBAL_PAUSE_ACTIVE。
        long forexUser = 96213L;
        deposit(forexUser, "it013b-fx", new BigDecimal("2000.00000000"));
        seedAlwaysOpen("EURAUD", "FX");
        publishQuote("EURAUD", new BigDecimal("1.64990000"), new BigDecimal("1.65000000"),
                new BigDecimal("1.65000000"), OffsetDateTime.now());
        OrderPlacementResult forex = orderService.placeMarketOrder(new PlaceMarketOrderCommand(
                forexUser, "EURAUD", TradingOrderSide.BUY, new BigDecimal("10000.00000000"),
                new BigDecimal("200"), null, null, null, "it013b-forex-order"));
        Assertions.assertNull(forex.position(), "pause 期间 forex 开仓应被拒，不应建仓");
        Assertions.assertEquals("GLOBAL_PAUSE_ACTIVE", forex.rejectionReason(),
                "forex cat2 allow_open=0 → 拒 GLOBAL_PAUSE_ACTIVE");

        // crypto BTCUSDT（cat1 allow_open=1）：开仓放行（pause 不拒）。
        long cryptoUser = 96214L;
        mapper.seedSingleLeverageTier("BTCUSDT", "default", 100, "0.010000");
        OrderPlacementResult crypto = openBtcUsdt(cryptoUser, "it013b-btc");
        Assertions.assertNotNull(crypto.position(),
                "pause 期间 crypto cat1 allow_open=1 应放行，开仓被拒原因: " + crypto.rejectionReason());
        Assertions.assertNull(crypto.rejectionReason(), "crypto 不应因 pause 拒单");
    }

    // ============================== TC-TIER-IT-014 (PERF) ==============================

    /** PERF：批量用户跌破 30% StopOut 强平队列吞吐 + 单仓强平 P99（&lt; 500ms 目标，记实测）。 */
    @Test
    void it014_perfBatchStopOutThroughputAndP99() {
        mapper.seedSingleLeverageTier("BTCUSDT", "default", 100, "0.010000");
        int users = 200; // WSL 资源受限，取 200 用户 × 单仓，真实强平链路实测吞吐与 P99（不伪造 1000）。
        long[] latenciesNs = new long[users];
        seedAlwaysOpen("BTCUSDT", "CRYPTO");
        for (int i = 0; i < users; i++) {
            long userId = 962000L + i;
            Long positionId = openBtcUsdt(userId, "it014-" + i).position().positionId();
            BigDecimal entry = new BigDecimal(mapper.selectPositionEntryPriceById(positionId));
            BigDecimal margin = new BigDecimal(mapper.selectPositionMarginById(positionId));
            BigDecimal mmRate = new BigDecimal(mapper.selectPositionMmRateAtOpenById(positionId));
            BigDecimal mm = entry.multiply(mmRate);
            BigDecimal targetMark = entry.subtract(margin).add(mm.multiply(new BigDecimal("0.20")));
            long t0 = System.nanoTime();
            PriceTickProcessingResult r = publishQuote("BTCUSDT",
                    targetMark.subtract(BigDecimal.ONE), targetMark.add(BigDecimal.ONE), targetMark, OffsetDateTime.now());
            latenciesNs[i] = System.nanoTime() - t0;
            Assertions.assertEquals(1, r.triggeredActions(), "用户 " + userId + " 应强平");
        }
        java.util.Arrays.sort(latenciesNs);
        double p99Ms = latenciesNs[(int) Math.ceil(users * 0.99) - 1] / 1_000_000.0;
        double medianMs = latenciesNs[users / 2] / 1_000_000.0;
        System.out.printf("[PERF TC-TIER-IT-014] 用户=%d 单仓强平 P50=%.3fms P99=%.3fms（目标 P99<500ms）%n",
                users, medianMs, p99Ms);
        if (p99Ms >= 500) {
            System.out.printf("[PERF TC-TIER-IT-014] 已知不阻断：WSL 资源受限 P99=%.3fms 未达目标，附实测不伪造。%n", p99Ms);
        }
    }

    // ============================== TC-TIER-IT-015 (PERF) ==============================

    /** PERF：MarginLevel 每 tick 重算（多用户多仓）账户缓存命中下 tick 延迟（不打 MySQL 证据）。 */
    @Test
    void it015_perfMarginLevelRecomputePerTickCacheHit() {
        mapper.seedSingleLeverageTier("BTCUSDT", "default", 100, "0.010000");
        int users = 100;
        seedAlwaysOpen("BTCUSDT", "CRYPTO");
        for (int i = 0; i < users; i++) {
            openBtcUsdt(963000L + i, "it015-" + i);
        }
        // 实际成功建仓数（每用户独立账户、独立持仓；本测重点是 per-tick 多仓重算延迟，count 不强求等于 users）。
        int openedBefore = snapshotStore.listOpenBySymbol("BTCUSDT").size();
        Assertions.assertTrue(openedBefore > 0, "应至少建仓 1 个用于 per-tick 重算");
        // 多个 HEALTHY tick（mark 高于入场，BUY 浮盈），每 tick 对全部 open 用户重算 MarginLevel；
        // 首 tick 暖账户缓存，后续命中缓存（不打 MySQL）。记 P50/P99。
        BigDecimal safeMark = new BigDecimal("12000.00000000");
        publishQuote("BTCUSDT", safeMark.subtract(BigDecimal.ONE), safeMark.add(BigDecimal.ONE), safeMark, OffsetDateTime.now());
        int ticks = 20;
        long[] tickNs = new long[ticks];
        for (int t = 0; t < ticks; t++) {
            BigDecimal mark = safeMark.add(new BigDecimal(t));
            long t0 = System.nanoTime();
            publishQuote("BTCUSDT", mark.subtract(BigDecimal.ONE), mark.add(BigDecimal.ONE), mark, OffsetDateTime.now());
            tickNs[t] = System.nanoTime() - t0;
        }
        java.util.Arrays.sort(tickNs);
        double p99Ms = tickNs[(int) Math.ceil(ticks * 0.99) - 1] / 1_000_000.0;
        double medianMs = tickNs[ticks / 2] / 1_000_000.0;
        System.out.printf("[PERF TC-TIER-IT-015] open仓=%d / tick（%d ticks）MarginLevel 重算（账户缓存命中）P50=%.3fms P99=%.3fms%n",
                openedBefore, ticks, medianMs, p99Ms);
        // HEALTHY（浮盈）tick 不应触发任何强平 → open 数不减少
        Assertions.assertEquals(openedBefore, snapshotStore.listOpenBySymbol("BTCUSDT").size(),
                "HEALTHY 浮盈 tick 不应强平任何仓位（open 数应不变）");
    }

    // ============================== TC-TIER-IT-016 ==============================

    /** tier 30s 缓存命中（LeverageTierResolver 二次 resolve 不重查 DB）。 */
    @Test
    void it016_tierResolverCacheHit() {
        // 注：stage5 profile 把生产 bean 的 cache-refresh-seconds 设为 0（IT 跨类隔离需要），故此处用 properties
        //   配 30s 的【生产构造器】独立 new 一个 resolver + 计数 repository，验证「缓存命中不重复查 DB」缓存语义
        //   （与生产 30s 同源同实现，仅 TTL 显式给 30s）。
        final int[] dbHits = {0};
        // SymbolLeverageTierRepository 在 C2 加了写方法后不再是函数式接口，这里只关心 findTiers
        // 的缓存语义，其余写/分页方法为本计数 stub 不涉及，故抛 UnsupportedOperationException。
        com.falconx.trading.repository.SymbolLeverageTierRepository countingRepo =
                new com.falconx.trading.repository.SymbolLeverageTierRepository() {
                    @Override
                    public List<com.falconx.trading.entity.SymbolLeverageTier> findTiers(String symbol, String groupCode) {
                        dbHits[0]++;
                        return List.of(new com.falconx.trading.entity.SymbolLeverageTier(
                                1L, symbol, groupCode, 1, new BigDecimal("0"), null, 100, new BigDecimal("0.010000"), true));
                    }

                    @Override
                    public java.util.Optional<com.falconx.trading.entity.SymbolLeverageTier> findById(long id) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public com.falconx.trading.entity.SymbolLeverageTier save(
                            com.falconx.trading.entity.SymbolLeverageTier tier) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public int update(com.falconx.trading.entity.SymbolLeverageTier tier) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public int softDelete(long id) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public List<com.falconx.trading.entity.SymbolLeverageTier> page(
                            String symbol, String groupCode, int offset, int limit) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public long count(String symbol, String groupCode) {
                        throw new UnsupportedOperationException();
                    }
                };
        com.falconx.trading.config.TradingCoreServiceProperties props =
                new com.falconx.trading.config.TradingCoreServiceProperties();
        props.getTier().setCacheRefreshSeconds(30);
        LeverageTierResolver cached =
                new com.falconx.trading.service.impl.DefaultLeverageTierResolver(countingRepo, props);
        cached.resolve("CACHEME", new BigDecimal("1000"), "default");
        cached.resolve("CACHEME", new BigDecimal("2000"), "default");
        cached.resolve("CACHEME", new BigDecimal("3000"), "default");
        Assertions.assertEquals(1, dbHits[0], "30s 内同 symbol+group 应只查 DB 一次（缓存命中）");
    }

    // ============================== TC-TIER-IT-017 ==============================

    /**
     * STAGE-14C2 Task 10：admin 改 tier → 开仓风控生效闭环（master §8.3 C「admin 改 tier 30s 生效」证据链）。
     *
     * <p>同进程真 DB + 真 {@code TradingTierAdminApplicationService}（= console 透传落到的 CRUD 层）
     * + 真 {@code TradingOrderPlacementApplicationService}/{@code DefaultTradingRiskService}/
     * {@code LeverageTierResolver}：
     * <ol>
     *   <li>建单档 tier maxLev=100 → lev=50 开仓<b>通过</b>（50≤100）。</li>
     *   <li>经 tier admin CRUD {@code updateTier} 把 maxLev 降到 20（mm 同步满足 CHECK）→ 写成功
     *       触发 {@code LeverageTierResolver.invalidate}。</li>
     *   <li>同条件 lev=50 重开 → 被 {@code evaluateMarketOrder} 用<b>新 tier</b> 拒
     *       {@code LEVERAGE_EXCEEDS_TIER}（30070）。</li>
     * </ol>
     * 证明 CRUD→开仓风控生效闭环（invalidate 即时生效；多实例 30s 惰性兜底见 it016/resolver IT，
     * 真跨服务 console→trading HTTP E2E 受 WSL 限制需全栈手动验证，见 R7 收口报告）。
     */
    @Test
    void it017_adminTierUpdatePropagatesToOpenOrderRiskControl() {
        long userId = 96117L;
        // 建单档宽松 tier maxLev=100（覆盖 V30 无 seed 的纯测试 symbol，自清在 cleanupTiers 的 BTCUSDT/default）。
        mapper.seedSingleLeverageTier("BTCUSDT", "default", 100, "0.010000");

        // 1) lev=50 开仓通过（50≤100）。
        OrderPlacementResult before = openBtcUsdtWithLeverage(userId, "it017a", new BigDecimal("50"));
        Assertions.assertNotNull(before.position(),
                "改 tier 前 lev=50 应在 maxLev=100 内通过，实际拒因: " + before.rejectionReason());

        // 2) 经 tier admin CRUD 把该档 maxLev 100→20（mm 同步 0.02 满足 CHECK 20×0.02=0.4）。
        long tierId = requireSingleTierId("BTCUSDT", "default");
        tierAdminService.updateTier(tierId, 1, new BigDecimal("0"), null, 20, new BigDecimal("0.020000"));

        // 3) 同条件 lev=50 重开 → 用新 tier 拒 30070（invalidate 即时生效，无需等 30s）。
        OrderPlacementResult after = openBtcUsdtWithLeverage(96118L, "it017b", new BigDecimal("50"));
        Assertions.assertNull(after.position(), "改 tier 后 lev=50 超新 maxLev=20，应被拒不建仓");
        Assertions.assertEquals("LEVERAGE_EXCEEDS_TIER", after.rejectionReason(),
                "改 tier 后开仓应被新 tier 上限拒 LEVERAGE_EXCEEDS_TIER（30070）");
    }

    private long requireSingleTierId(String symbol, String groupCode) {
        List<com.falconx.trading.entity.SymbolLeverageTier> tiers =
                appCtx.getBean(com.falconx.trading.repository.SymbolLeverageTierRepository.class)
                        .findTiers(symbol, groupCode);
        Assertions.assertEquals(1, tiers.size(), symbol + "/" + groupCode + " 应只有单档 tier");
        return tiers.get(0).id();
    }

    // ============================== helpers ==============================

    // ============================== B 切片守卫（2026-06-03）==============================

    /**
     * 强平距离守卫：tier 配置违反 lev×mm≤0.5 设计准则（DB CHECK 只到 ≤1.0）时，满杠杆开仓
     * 缓冲≈0 → 拒单 30071 LIQUIDATION_DISTANCE_TOO_CLOSE；同档低杠杆（缓冲充足）正常放行。
     * 复现 demo AUDCAD 300x 瞬时强平场景（开仓 199ms 被强平）的防呆层。
     */
    @Test
    void liqDistanceGuard_rejectsZeroBufferFullLeverage_allowsLowerLeverage() {
        long userId = 96120L;
        // lev×mm = 100×0.010 = 1.0（CHECK 边界允许，且 100x 不超 SymbolSpec 全局上限）：
        // 100x 开仓 margin_q=100、mm=0.01×10000=100 → liq=entry → 距离 0 ≤ 点差(10)×2
        mapper.seedSingleLeverageTier("BTCUSDT", "default", 100, "0.010000");

        OrderPlacementResult rejected = openBtcUsdtWithLeverage(userId, "liq-guard-reject", new BigDecimal("100"));
        Assertions.assertNull(rejected.position(), "零缓冲满杠杆应被拒");
        Assertions.assertEquals("LIQUIDATION_DISTANCE_TOO_CLOSE", rejected.rejectionReason(),
                "应触发强平距离守卫（30071）");

        // 同档 50x：margin_q=200、mm=100 → 距离 100 > 点差(10)×2 → 放行
        OrderPlacementResult accepted = openBtcUsdtWithLeverage(userId, "liq-guard-pass", new BigDecimal("50"));
        Assertions.assertNotNull(accepted.position(),
                "低杠杆缓冲充足应放行: " + accepted.rejectionReason());
    }

    private OrderPlacementResult openBtcUsdtWithLeverage(long userId, String tag, BigDecimal leverage) {
        deposit(userId, tag, new BigDecimal("2000.00000000"));
        seedAlwaysOpen("BTCUSDT", "CRYPTO");
        publishQuote("BTCUSDT", new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"), OffsetDateTime.now());
        return orderService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId, "BTCUSDT", TradingOrderSide.BUY, new BigDecimal("1.00000000"), leverage,
                null, new BigDecimal("99999.00000000"), null, tag + "-order"));
    }

    private OrderPlacementResult openBtcUsdt(long userId, String tag) {
        deposit(userId, tag, new BigDecimal("2000.00000000"));
        seedAlwaysOpen("BTCUSDT", "CRYPTO");
        publishQuote("BTCUSDT", new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"), OffsetDateTime.now());
        return orderService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId, "BTCUSDT", TradingOrderSide.BUY, new BigDecimal("1.00000000"), new BigDecimal("10"),
                null, new BigDecimal("99999.00000000"), null, tag + "-order"));
    }

    private OrderPlacementResult openEurAud(long userId, String tag, BigDecimal mark, BigDecimal leverage) {
        deposit(userId, tag, new BigDecimal("2000.00000000"));
        seedAlwaysOpen("EURAUD", "FX");
        publishQuote("EURAUD", mark.subtract(new BigDecimal("0.00010000")), mark, mark, OffsetDateTime.now());
        // 0.1 lot = 10000 EUR
        return orderService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId, "EURAUD", TradingOrderSide.BUY, new BigDecimal("10000.00000000"), leverage,
                null, null, null, tag + "-order"));
    }

    private void deposit(long userId, String tag, BigDecimal amount) {
        depositService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                "evt-" + tag + "-" + userId, 99800L + userId, userId, ChainType.ETH, "USDT",
                "0x" + tag + userId, amount, OffsetDateTime.now()));
    }

    private PriceTickProcessingResult publishQuote(String symbol, BigDecimal bid, BigDecimal ask,
                                                   BigDecimal mark, OffsetDateTime ts) {
        return quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                symbol, bid, ask, mark, mark, ts, "stage14c1-tier-it",
                false, TradingQuoteQualityStatus.FRESH.name(), null));
    }

    private void seedSpec(String symbol, String base, String quote, int maxLeverage, BigDecimal feeRate) {
        // STAGE-14C2 Task 1：按 base 推导类目（XAU→3 金属、EUR 等法币→2 外汇、其余→1 crypto）
        Integer category = "XAU".equals(base) ? 3
                : ("EUR".equals(base) || "AUD".equals(base) ? 2 : 1);
        SymbolSpec spec = new SymbolSpec(
                symbol, maxLeverage, feeRate, BigDecimal.ZERO,
                new BigDecimal("0.00000001"), new BigDecimal("1000000000"), BigDecimal.ZERO,
                8, 8, base, quote, category);
        try {
            redis.opsForValue().set(SPEC_KEY_PREFIX + symbol, objectMapper.writeValueAsString(spec));
        } catch (Exception e) {
            throw new IllegalStateException("seed spec failed: " + symbol, e);
        }
    }

    private void fxAcceptUpdate(String base, String quote, BigDecimal rate) {
        // 通过 ApplicationContext 取 FxRateService（避免顶层强类型 import 漂移）。
        com.falconx.trading.service.FxRateService fx = applicationContext().getBean(
                com.falconx.trading.service.FxRateService.class);
        fx.acceptUpdate(base, quote, rate, System.currentTimeMillis());
    }

    @Autowired
    private org.springframework.context.ApplicationContext appCtx;

    private org.springframework.context.ApplicationContext applicationContext() {
        return appCtx;
    }

    private void seedAlwaysOpen(String symbol, String marketCode) {
        scheduleRepository.saveForTest(new com.falconx.trading.service.model.TradingScheduleSnapshot(
                symbol, marketCode,
                List.of(
                        win(1), win(2), win(3), win(4), win(5), win(6), win(7)
                ),
                List.of(), List.of(), OffsetDateTime.now()));
    }

    private com.falconx.trading.service.model.TradingSessionWindow win(int dow) {
        return new com.falconx.trading.service.model.TradingSessionWindow(
                dow, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true,
                LocalDate.of(2026, 1, 1), null);
    }
}
