package com.falconx.trading;

import com.falconx.domain.enums.ChainType;
import com.falconx.market.contract.SymbolSpec;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingDepositCreditApplicationService;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.application.TradingPositionCloseApplicationService;
import com.falconx.trading.application.TradingPositionMarginApplicationService;
import com.falconx.trading.command.AddIsolatedMarginCommand;
import com.falconx.trading.command.CloseTradingPositionCommand;
import com.falconx.trading.command.CreditConfirmedDepositCommand;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.dto.OrderPlacementResult;
import com.falconx.trading.dto.PositionCloseResult;
import com.falconx.trading.dto.PriceTickProcessingResult;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.QuoteDrivenEngine;
import com.falconx.trading.entity.FxPauseBehavior;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingQuoteQualityStatus;
import com.falconx.trading.entity.TradingRiskControlActionType;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.repository.FxPauseBehaviorRepository;
import com.falconx.trading.repository.RedisTradingScheduleSnapshotRepository;
import com.falconx.trading.repository.TradingRiskControlActionRepository;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * STAGE-14D3a Task 8：FX_PAUSED 8 类目 × 3 开关组合完整验收 IT
 * （master §8.3 D 阶段硬约束「FX_PAUSED 8 类目×3 开关组合行为正确」的 R6/R7 证据）。
 *
 * <p>真 MySQL（共享 {@code falconx_trading_it}，含 V31 真 {@code t_fx_pause_behavior} 8 行 seed）+ 真 Redis
 * + 真引擎链路。激活 GLOBAL_PAUSE（{@code activateIfAbsent(null, GLOBAL_PAUSE)}），按品种类目
 * {@code category} 查 {@code t_fx_pause_behavior} 决定行为：
 * <ul>
 *   <li><b>allow_open</b>（开仓 + supplement 共用）：=0（默认 forex/metal）→ 开仓拒 30087 且 supplement 拒 30087；
 *       =1（默认其余 6）→ 开仓放行（继续品种级风控）+ supplement 放行。</li>
 *   <li><b>allow_close</b>（master §6.5 恒 1）：pause 下手动平仓始终成功（含 allow_open=0 的 forex）。</li>
 *   <li><b>allow_liquidation</b>：=0（默认 forex/metal）→ 被动强平 skip；=1（默认其余）→ 被动强平执行。</li>
 *   <li><b>admin override 可配生效</b>：经 {@code FxPauseBehaviorRepository.updateByCategory}（= console 写路径，
 *       写后失效本地缓存即时生效）把 forex(2) 翻全开 → 开仓放行；把 crypto(1) 翻全停 → 开仓拒 30087。</li>
 *   <li><b>降级</b>：category=null / behavior 缺失 → 开仓保守全拒（沿 C2 口径）。</li>
 * </ul>
 *
 * <h2>覆盖边界（据实标注，不伪造）</h2>
 * <ul>
 *   <li><b>全链（真 DB + 真引擎）证</b>：allow_open 开仓维度（8 类目参数化，经
 *       {@code TradingOrderPlacementApplicationService}）、supplement 维度（经
 *       {@code TradingPositionMarginApplicationService.addIsolatedMargin}）、allow_close（经
 *       {@code TradingPositionCloseApplicationService.closePosition}）、admin override 可配生效、降级 category=null。
 *       allow_liquidation 被动强平 skip/执行经 {@code QuoteDrivenEngine.processTick} 真 tick 各覆盖 1 类目。</li>
 *   <li>合成 symbol（quote=USDT=账户币，FX=1）按 category 1-8 各建一只，category 由直写 Redis 的 SymbolSpec
 *       显式指定，<b>不碰共享库 t_fx_pause_behavior 8 行 seed、不动真实 symbol seed</b>。admin override
 *       直改 t_fx_pause_behavior 行 → 用例内 finally 复位 + 失效缓存，避免污染共享库与其余 IT。</li>
 *   <li>「allow_liquidation=1 执行」与「=0 skip」的其余 6 类目，行为开关读取正确已由本类默认矩阵
 *       开仓断言（8 类目）+ C2 {@code QuoteDrivenEngineMarginLevelTriggerTests}（mock 全 8 类目分支）覆盖；
 *       本 IT 各取 1 类目（crypto 执行 / forex skip）做真 tick 全链证。</li>
 * </ul>
 *
 * <p>GLOBAL_PAUSE 记录由下一个 {@code @BeforeEach}（{@code clearOwnerTables → deleteRiskControlAction}）清理，
 * 不跨用例泄漏。
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
class FxPauseBehavior8x3AcceptanceIntegrationTests {

    private static final String SPEC_KEY_PREFIX = "falconx:market:symbol-spec:";

    /** category 编码（1-8） → 类目名（与 V31 seed 一致）。 */
    private static final String[] CATEGORY_NAMES = {
            null, "crypto", "forex", "metal", "index", "energy", "stock", "etf", "other"
    };
    /** V31 默认 seed：forex(2)/metal(3) allow_open=0/allow_liquidation=0，其余全 1。 */
    private static final List<Integer> DEFAULT_DISALLOW_OPEN = List.of(2, 3);

    @Autowired
    private TradingDepositCreditApplicationService depositService;
    @Autowired
    private TradingOrderPlacementApplicationService orderService;
    @Autowired
    private TradingPositionMarginApplicationService marginService;
    @Autowired
    private TradingPositionCloseApplicationService closeService;
    @Autowired
    private QuoteDrivenEngine quoteDrivenEngine;
    @Autowired
    private TradingTestSupportMapper mapper;
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RedisTradingScheduleSnapshotRepository scheduleRepository;
    @Autowired
    private OpenPositionSnapshotStore snapshotStore;
    @Autowired
    private TradingRiskControlActionRepository riskControlActionRepository;
    @Autowired
    private FxPauseBehaviorRepository fxPauseBehaviorRepository;

    @BeforeEach
    void clean() {
        mapper.clearOwnerTables();
        snapshotStore.replaceAll(List.of());
        for (int cat = 1; cat <= 8; cat++) {
            String s = synSymbol(cat);
            redis.delete("falconx:trading:quote:snapshot:" + s);
            redis.delete("falconx:market:trading:schedule:" + s);
        }
    }

    @AfterEach
    void cleanupTiers() {
        for (int cat = 1; cat <= 8; cat++) {
            mapper.deleteLeverageTierBySymbolAndGroup(synSymbol(cat), "default");
        }
    }

    // ========================================================================
    // 维度 1：allow_open（开仓 + supplement 共用此开关）— 8 类目系统化参数化
    // ========================================================================

    /**
     * allow_open 维度（开仓）：pause 活跃下，对 8 个 category 逐一断言默认 seed 矩阵行为。
     * forex(2)/metal(3) allow_open=0 → 开仓拒 30087（GLOBAL_PAUSE_ACTIVE）；
     * 其余 6 类目 allow_open=1 → 开仓放行（建仓成功，继续品种级风控）。
     */
    @ParameterizedTest(name = "category={0} 开仓 FX_PAUSED allow_open 矩阵")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void open_fxPaused_allowOpenMatrix_perCategory(int category) {
        activateGlobalPause("open-matrix-cat" + category);
        long userId = 980000L + category;
        OrderPlacementResult r = openSyn(userId, category, "open-cat" + category);

        if (DEFAULT_DISALLOW_OPEN.contains(category)) {
            Assertions.assertNull(r.position(),
                    "category=" + category + "(" + CATEGORY_NAMES[category]
                            + ") allow_open=0 pause 下开仓应被拒，不应建仓");
            Assertions.assertEquals("GLOBAL_PAUSE_ACTIVE", r.rejectionReason(),
                    "category=" + category + " allow_open=0 → 拒 GLOBAL_PAUSE_ACTIVE(30087)");
        } else {
            Assertions.assertNotNull(r.position(),
                    "category=" + category + "(" + CATEGORY_NAMES[category]
                            + ") allow_open=1 pause 下开仓应放行，拒因: " + r.rejectionReason());
            Assertions.assertNull(r.rejectionReason(),
                    "category=" + category + " allow_open=1 不应因 pause 拒单");
        }
    }

    /**
     * allow_open 维度（supplement = 开仓侧加保证金，共用 allow_open 闸门）：
     * 先在<b>无 pause</b> 下建一只 ISOLATED 仓，再激活 pause 做 addIsolatedMargin。
     * forex(2)/metal(3) → supplement 拒 30087；其余 6 类目 → supplement 放行（推进到余额校验后成功补仓）。
     */
    @ParameterizedTest(name = "category={0} supplement FX_PAUSED allow_open 矩阵")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void supplement_fxPaused_allowOpenMatrix_perCategory(int category) {
        long userId = 981000L + category;
        // 1) 无 pause 先建一只 ISOLATED 仓（充足余额，供后续补仓）。
        OrderPlacementResult opened = openSyn(userId, category, "supp-open-cat" + category);
        Assertions.assertNotNull(opened.position(),
                "category=" + category + " 无 pause 建仓应成功（供 supplement），拒因: " + opened.rejectionReason());
        Long positionId = opened.position().positionId();

        // 2) 激活 pause 后补仓。
        activateGlobalPause("supp-matrix-cat" + category);
        AddIsolatedMarginCommand cmd = new AddIsolatedMarginCommand(userId, positionId, new BigDecimal("50"));

        if (DEFAULT_DISALLOW_OPEN.contains(category)) {
            TradingBusinessException ex = Assertions.assertThrows(TradingBusinessException.class,
                    () -> marginService.addIsolatedMargin(cmd),
                    "category=" + category + " allow_open=0 pause 下 supplement 应拒 30087");
            Assertions.assertEquals(com.falconx.trading.error.TradingErrorCode.GLOBAL_PAUSE_ACTIVE,
                    ex.getErrorCode(), "category=" + category + " supplement 拒因应为 GLOBAL_PAUSE_ACTIVE(30087)");
        } else {
            // allow_open=1 → gating 放行，补仓成功（余额充足，推进过余额校验）。
            Assertions.assertDoesNotThrow(() -> marginService.addIsolatedMargin(cmd),
                    "category=" + category + " allow_open=1 pause 下 supplement 应放行");
        }
    }

    // ========================================================================
    // 维度 2：allow_close（master §6.5 恒 1）— pause 下手动平仓始终成功
    // ========================================================================

    /**
     * allow_close 维度：恒为 1 → pause 下手动平仓始终成功。
     * 覆盖 forex(2)（allow_open=0，停盘期间仅允许平仓的代表）与 crypto(1)（allow_open=1）。
     * 手动平仓链路（{@code TradingPositionCloseApplicationService.closePosition}）无 pause 闸门 ——
     * 结构性保证 allow_close=1（pause 不阻断手动平仓）。
     */
    @Test
    void close_fxPaused_alwaysSucceeds_forexAndCrypto() {
        // forex(2)：allow_open=0 但仍可手动平仓。
        assertManualCloseSucceedsUnderPause(2, 982002L, "close-forex");
        // crypto(1)：allow_open=1，同样可手动平仓（回归）。
        assertManualCloseSucceedsUnderPause(1, 982001L, "close-crypto");
    }

    private void assertManualCloseSucceedsUnderPause(int category, long userId, String tag) {
        // 无 pause 建仓。
        OrderPlacementResult opened = openSyn(userId, category, tag);
        Assertions.assertNotNull(opened.position(),
                "category=" + category + " 建仓应成功，拒因: " + opened.rejectionReason());
        Long positionId = opened.position().positionId();

        // 激活 pause 后手动平仓应成功（allow_close 恒 1）。
        activateGlobalPause(tag + "-pause");
        PositionCloseResult closed = closeService.closePosition(
                new CloseTradingPositionCommand(userId, positionId));
        Assertions.assertNotNull(closed, "category=" + category + " pause 下手动平仓应成功（allow_close=1）");
        // status=CLOSED(2)、close_reason=MANUAL(1)。
        Assertions.assertEquals(2, mapper.selectPositionStatusCodeById(positionId),
                "category=" + category + " 手动平仓 status 应为 CLOSED(2)");
        Assertions.assertEquals(1, mapper.selectPositionCloseReasonCodeById(positionId),
                "category=" + category + " 手动平仓 close_reason 应为 MANUAL(1)");
        Assertions.assertTrue(snapshotStore.listOpenByUserId(userId).isEmpty(),
                "category=" + category + " 手动平仓后该用户应无 OPEN 仓");
        // 清理 pause（下个用例 @BeforeEach 也会清，这里立即清避免影响同方法第二段）。
        riskControlActionRepository.deactivate(null, TradingRiskControlActionType.GLOBAL_PAUSE, "it-cleanup");
    }

    // ========================================================================
    // 维度 3：allow_liquidation — 被动强平 skip（forex）/ 执行（crypto）真 tick 全链
    // ========================================================================

    /**
     * allow_liquidation=0（forex cat2 默认）：pause 下被动强平 <b>skip</b>。
     * 建仓后激活 pause，喂深破行情使单仓 MarginLevel ≤ stopOut → 本应强平，但因 allow_liquidation=0 跳过。
     */
    @Test
    void liquidation_fxPaused_forexAllowLiquidationFalse_skips() {
        int category = 2; // forex，allow_liquidation=0
        long userId = 983002L;
        Long positionId = openSyn(userId, category, "liq-skip-forex").position().positionId();
        Assertions.assertEquals(1, snapshotStore.listOpenByUserId(userId).size());

        activateGlobalPause("liq-skip-forex-pause");
        // 深破 mark → ML ≤ stopOut；但 forex allow_liquidation=0 → skip。
        BigDecimal breach = breachMark(positionId);
        PriceTickProcessingResult r = publishQuote(synSymbol(category),
                breach.subtract(BigDecimal.ONE), breach.add(BigDecimal.ONE), breach, OffsetDateTime.now());

        Assertions.assertEquals(0, r.triggeredActions(),
                "forex allow_liquidation=0 → pause 下被动强平应 skip，实际触发=" + r.triggeredActions());
        Assertions.assertEquals(1, snapshotStore.listOpenByUserId(userId).size(),
                "skip 后仓位应仍 OPEN");
        Assertions.assertEquals(1, mapper.selectPositionStatusCodeById(positionId),
                "skip 后 status 应仍 OPEN(1)");
    }

    /**
     * allow_liquidation=1（crypto cat1 默认）：pause 下被动强平 <b>执行</b>。
     * 同样建仓 + pause + 深破，crypto 类目允许强平 → 正常强平落账（close_reason=LIQUIDATION）。
     */
    @Test
    void liquidation_fxPaused_cryptoAllowLiquidationTrue_executes() {
        int category = 1; // crypto，allow_liquidation=1
        long userId = 983001L;
        Long positionId = openSyn(userId, category, "liq-exec-crypto").position().positionId();
        Assertions.assertEquals(1, snapshotStore.listOpenByUserId(userId).size());

        activateGlobalPause("liq-exec-crypto-pause");
        BigDecimal breach = breachMark(positionId);
        PriceTickProcessingResult r = publishQuote(synSymbol(category),
                breach.subtract(BigDecimal.ONE), breach.add(BigDecimal.ONE), breach, OffsetDateTime.now());

        Assertions.assertEquals(1, r.triggeredActions(),
                "crypto allow_liquidation=1 → pause 下被动强平应执行，实际触发=" + r.triggeredActions());
        Assertions.assertEquals(3, mapper.selectPositionStatusCodeById(positionId),
                "强平后 status 应为 LIQUIDATED(3)");
        Assertions.assertEquals(4, mapper.selectPositionCloseReasonCodeById(positionId),
                "强平 close_reason 应为 LIQUIDATION(4)");
        Assertions.assertTrue(snapshotStore.listOpenByUserId(userId).isEmpty(),
                "强平后该用户应无 OPEN 仓");
    }

    // ========================================================================
    // 维度 4：admin override 可配生效（updateByCategory 写后缓存失效即时生效）
    // ========================================================================

    /**
     * admin override：经 {@code FxPauseBehaviorRepository.updateByCategory}（= console 写路径，写后失效本地缓存）
     * 把 forex(2) 翻全开 → pause 下开仓放行；把 crypto(1) 翻全停 → pause 下开仓拒 30087。
     * 证明 admin 改后即时生效（缓存失效），不等 TTL。用例内复位两行 + 失效缓存，避免污染共享库/缓存。
     */
    @Test
    void adminOverride_takesEffectImmediately_forexOpenedCryptoStopped() {
        // 快照原值（复位用）。
        FxPauseBehavior orig2 = fxPauseBehaviorRepository.findByCategory(2).orElseThrow();
        FxPauseBehavior orig1 = fxPauseBehaviorRepository.findByCategory(1).orElseThrow();
        try {
            // forex(2) 翻全开（allow_open=true）。
            fxPauseBehaviorRepository.updateByCategory(2, true, true, true, 9001L);
            // crypto(1) 翻全停（allow_open=false）。
            fxPauseBehaviorRepository.updateByCategory(1, false, true, false, 9001L);
            // 写后缓存即失效，立即可见。
            Assertions.assertTrue(fxPauseBehaviorRepository.findByCategory(2).orElseThrow().allowOpen(),
                    "override 后 forex(2) allow_open 应即时为 true");
            Assertions.assertFalse(fxPauseBehaviorRepository.findByCategory(1).orElseThrow().allowOpen(),
                    "override 后 crypto(1) allow_open 应即时为 false");

            activateGlobalPause("admin-override");

            // forex(2) 翻全开后：pause 下开仓放行（默认是拒）。
            OrderPlacementResult forex = openSyn(984002L, 2, "override-forex");
            Assertions.assertNotNull(forex.position(),
                    "override 后 forex(2) allow_open=true pause 下开仓应放行，拒因: " + forex.rejectionReason());

            // crypto(1) 翻全停后：pause 下开仓拒 30087（默认是放行）。
            OrderPlacementResult crypto = openSyn(984001L, 1, "override-crypto");
            Assertions.assertNull(crypto.position(),
                    "override 后 crypto(1) allow_open=false pause 下开仓应被拒");
            Assertions.assertEquals("GLOBAL_PAUSE_ACTIVE", crypto.rejectionReason(),
                    "override 后 crypto(1) → 拒 GLOBAL_PAUSE_ACTIVE(30087)");
        } finally {
            // 复位 t_fx_pause_behavior 两行 + 失效缓存，避免污染共享库与其余 IT。
            fxPauseBehaviorRepository.updateByCategory(2, orig2.allowOpen(), orig2.allowClose(),
                    orig2.allowLiquidation(), null);
            fxPauseBehaviorRepository.updateByCategory(1, orig1.allowOpen(), orig1.allowClose(),
                    orig1.allowLiquidation(), null);
        }
    }

    // ========================================================================
    // 维度 5：降级 — category=null 开仓保守全拒 / 被动强平继续（C2 口径各 1 次）
    // ========================================================================

    /**
     * 降级（开仓方向）：pause 活跃 + category=null（过渡期旧快照）→ 开仓保守全拒
     * （C2 口径：BBOOK_RISK_GLOBAL_PAUSE）。用一只 category=null 的合成 symbol。
     */
    @Test
    void degraded_fxPaused_categoryNull_openConservativelyRejected() {
        long userId = 985001L;
        String nullCatSymbol = "SYNNULLCAT";
        seedSpecRaw(nullCatSymbol, "SYN", "USDT", 100, null);
        freeSyntheticTierId();
        mapper.seedSingleLeverageTier(nullCatSymbol, "default", 100, "0.010000");
        seedAlwaysOpen(nullCatSymbol, "CRYPTO");
        deposit(userId, "deg-null", new BigDecimal("2000.00000000"));
        publishQuote(nullCatSymbol, new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"), OffsetDateTime.now());

        activateGlobalPause("deg-null-pause");
        OrderPlacementResult r = orderService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId, nullCatSymbol, TradingOrderSide.BUY, new BigDecimal("1.00000000"),
                new BigDecimal("10"), null, new BigDecimal("99999.00000000"), null, "deg-null-order"));
        Assertions.assertNull(r.position(), "category=null pause 下开仓应保守全拒，不应建仓");
        Assertions.assertEquals("BBOOK_RISK_GLOBAL_PAUSE", r.rejectionReason(),
                "category=null 保守降级 → 拒 BBOOK_RISK_GLOBAL_PAUSE");

        mapper.deleteLeverageTierBySymbolAndGroup(nullCatSymbol, "default");
        redis.delete("falconx:trading:quote:snapshot:" + nullCatSymbol);
        redis.delete("falconx:market:trading:schedule:" + nullCatSymbol);
    }

    /**
     * 降级（强平方向，与开仓相反）：pause 活跃 + category=null → 被动强平 <b>继续</b>
     * （C2 口径：不因缺信息阻止强平）。用一只 category=null 的合成 symbol 深破触发。
     */
    @Test
    void degraded_fxPaused_categoryNull_liquidationStillExecutes() {
        long userId = 985002L;
        String nullCatSymbol = "SYNNULLLIQ";
        seedSpecRaw(nullCatSymbol, "SYN", "USDT", 100, null);
        freeSyntheticTierId();
        mapper.seedSingleLeverageTier(nullCatSymbol, "default", 100, "0.010000");
        seedAlwaysOpen(nullCatSymbol, "CRYPTO");
        deposit(userId, "deg-null-liq", new BigDecimal("2000.00000000"));
        publishQuote(nullCatSymbol, new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"), OffsetDateTime.now());
        OrderPlacementResult opened = orderService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId, nullCatSymbol, TradingOrderSide.BUY, new BigDecimal("1.00000000"),
                new BigDecimal("10"), null, new BigDecimal("99999.00000000"), null, "deg-null-liq-order"));
        Assertions.assertNotNull(opened.position(), "建仓应成功，拒因: " + opened.rejectionReason());
        Long positionId = opened.position().positionId();

        activateGlobalPause("deg-null-liq-pause");
        BigDecimal breach = breachMark(positionId);
        PriceTickProcessingResult r = publishQuote(nullCatSymbol,
                breach.subtract(BigDecimal.ONE), breach.add(BigDecimal.ONE), breach, OffsetDateTime.now());

        Assertions.assertEquals(1, r.triggeredActions(),
                "category=null 降级 → 被动强平应继续执行（不因缺信息阻止），实际触发=" + r.triggeredActions());
        Assertions.assertEquals(3, mapper.selectPositionStatusCodeById(positionId),
                "强平后 status 应为 LIQUIDATED(3)");

        mapper.deleteLeverageTierBySymbolAndGroup(nullCatSymbol, "default");
        redis.delete("falconx:trading:quote:snapshot:" + nullCatSymbol);
        redis.delete("falconx:market:trading:schedule:" + nullCatSymbol);
    }

    // ============================== helpers ==============================

    /** 合成 symbol 名（按 category 1-8 各一只，quote=USDT=账户币，FX=1）。 */
    private static String synSymbol(int category) {
        return "SYNCAT" + category + "USDT";
    }

    /** 释放固定 tier id（39900001）：清掉本类所有合成 symbol 的 default tier，供下一次 seedSingleLeverageTier。 */
    private void freeSyntheticTierId() {
        for (int cat = 1; cat <= 8; cat++) {
            mapper.deleteLeverageTierBySymbolAndGroup(synSymbol(cat), "default");
        }
        mapper.deleteLeverageTierBySymbolAndGroup("SYNNULLCAT", "default");
        mapper.deleteLeverageTierBySymbolAndGroup("SYNNULLLIQ", "default");
    }

    /** 激活全局 GLOBAL_PAUSE（symbol=null）。 */
    private void activateGlobalPause(String tag) {
        boolean activated = riskControlActionRepository.activateIfAbsent(
                null, TradingRiskControlActionType.GLOBAL_PAUSE, "MANUAL_ADMIN", tag, null);
        Assertions.assertTrue(riskControlActionRepository.hasActiveGlobalPause(),
                "GLOBAL_PAUSE 应处于激活态（tag=" + tag + ", activated=" + activated + ")");
    }

    /** 为某 category 合成 symbol 充值 + seed tier/schedule，并以 BUY 10x qty=1 开 ISOLATED 仓（mark=10000）。 */
    private OrderPlacementResult openSyn(long userId, int category, String tag) {
        String symbol = synSymbol(category);
        seedSpecRaw(symbol, "SYN", "USDT", 100, category);
        // insertSingleLeverageTier 用固定 id（39900001）。一个测试方法内多次开仓（不同 category）会撞 PK，
        //   故 seed 前先释放该 id：清掉本类所有合成 symbol 的 default tier（含上次 open 占用的那只）。
        freeSyntheticTierId();
        mapper.seedSingleLeverageTier(symbol, "default", 100, "0.010000");
        seedAlwaysOpen(symbol, "CRYPTO");
        deposit(userId, tag, new BigDecimal("2000.00000000"));
        publishQuote(symbol, new BigDecimal("9990.00000000"), new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"), OffsetDateTime.now());
        return orderService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId, symbol, TradingOrderSide.BUY, new BigDecimal("1.00000000"), new BigDecimal("10"),
                TradingMarginMode.ISOLATED, new BigDecimal("99999.00000000"), null, tag + "-order"));
    }

    /** 解 mark 使该 BUY 仓单仓 MarginLevel ≈ 20%（深破 ≤ stopOut 30%）。 */
    private BigDecimal breachMark(Long positionId) {
        BigDecimal entry = new BigDecimal(mapper.selectPositionEntryPriceById(positionId));
        BigDecimal margin = new BigDecimal(mapper.selectPositionMarginById(positionId));
        BigDecimal mmRate = new BigDecimal(mapper.selectPositionMmRateAtOpenById(positionId));
        BigDecimal mm = entry.multiply(mmRate);
        // 单仓 BUY qty=1：ML=(margin+(mark-entry))/MM×100；取 ML=20% 解 mark。
        return entry.subtract(margin).add(mm.multiply(new BigDecimal("0.20")));
    }

    private void deposit(long userId, String tag, BigDecimal amount) {
        depositService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                "evt-" + tag + "-" + userId, 99800L + userId, userId, ChainType.ETH, "USDT",
                "0x" + tag + userId, amount, OffsetDateTime.now()));
    }

    private PriceTickProcessingResult publishQuote(String symbol, BigDecimal bid, BigDecimal ask,
                                                   BigDecimal mark, OffsetDateTime ts) {
        return quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                symbol, bid, ask, mark, mark, ts, "stage14d3a-fxpause-it",
                false, TradingQuoteQualityStatus.FRESH.name(), null));
    }

    /** 直写 Redis 的 SymbolSpec，category 显式指定（含 null）。 */
    private void seedSpecRaw(String symbol, String base, String quote, int maxLeverage, Integer category) {
        SymbolSpec spec = new SymbolSpec(
                symbol, maxLeverage, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.00000001"), new BigDecimal("1000000000"), BigDecimal.ZERO,
                8, 8, base, quote, category);
        try {
            redis.opsForValue().set(SPEC_KEY_PREFIX + symbol, objectMapper.writeValueAsString(spec));
        } catch (Exception e) {
            throw new IllegalStateException("seed spec failed: " + symbol, e);
        }
    }

    private void seedAlwaysOpen(String symbol, String marketCode) {
        scheduleRepository.saveForTest(new com.falconx.trading.service.model.TradingScheduleSnapshot(
                symbol, marketCode,
                List.of(win(1), win(2), win(3), win(4), win(5), win(6), win(7)),
                List.of(), List.of(), OffsetDateTime.now()));
    }

    private com.falconx.trading.service.model.TradingSessionWindow win(int dow) {
        return new com.falconx.trading.service.model.TradingSessionWindow(
                dow, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true,
                LocalDate.of(2026, 1, 1), null);
    }
}
