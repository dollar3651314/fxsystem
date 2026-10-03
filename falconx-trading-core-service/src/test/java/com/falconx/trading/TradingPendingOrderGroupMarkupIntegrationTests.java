package com.falconx.trading;

import com.falconx.domain.enums.ChainType;
import com.falconx.market.contract.event.MarketGroupMarkupItem;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingDepositCreditApplicationService;
import com.falconx.trading.application.TradingPendingOrderApplicationService;
import com.falconx.trading.command.CreditConfirmedDepositCommand;
import com.falconx.trading.engine.PendingOrderTriggerEvaluator;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPendingOrderTrigger;
import com.falconx.trading.entity.TradingPendingOrderType;
import com.falconx.trading.entity.TradingQuoteQualityStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.repository.RedisTradingScheduleSnapshotRepository;
import com.falconx.trading.repository.TradingPendingOrderTriggerRepository;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import com.falconx.trading.service.TradingGroupMarkupService;
import com.falconx.trading.service.model.TradingScheduleSnapshot;
import com.falconx.trading.service.model.TradingSessionWindow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * STAGE-12-GROUP-MARKUP TC-GM-020~022 挂单触发按冻结 markup 集成测试。
 *
 * <p>验证：
 * <ul>
 *   <li>TC-GM-020 LIMIT/STOP/STOP_LIMIT 创建时冻结 (groupCode, bidExtra, askExtra) 落 t_pending_order_trigger</li>
 *   <li>TC-GM-022 触发判定按冻结值（管理端改加点不影响存量挂单）— 通过 evaluator 直接调用验证</li>
 * </ul>
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
class TradingPendingOrderGroupMarkupIntegrationTests {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-05-21T00:00:00Z");

    @Autowired
    private TradingDepositCreditApplicationService tradingDepositCreditApplicationService;

    @Autowired
    private TradingPendingOrderApplicationService tradingPendingOrderApplicationService;

    @Autowired
    private TradingPendingOrderTriggerRepository pendingOrderRepository;

    @Autowired
    private PendingOrderTriggerEvaluator pendingOrderTriggerEvaluator;

    @Autowired
    private TradingTestSupportMapper tradingTestSupportMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisTradingScheduleSnapshotRepository tradingScheduleSnapshotRepository;

    @MockitoBean
    private TradingGroupMarkupService tradingGroupMarkupService;

    @BeforeEach
    void cleanTradingStores() {
        tradingTestSupportMapper.clearOwnerTables();
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:BTCUSDT");
        stringRedisTemplate.delete("falconx:market:trading:schedule:BTCUSDT");

        Mockito.reset(tradingGroupMarkupService);
        Mockito.when(tradingGroupMarkupService.find(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(Optional.empty());

        seedAlwaysOpenSchedule("BTCUSDT", "CRYPTO");
    }

    /** TC-GM-020 LIMIT 创建时冻结 groupCodeAtCreate / bidExtraAtCreate / askExtraAtCreate。 */
    @Test
    void TC_GM_020_limit_create_freezes_group_markup() {
        Mockito.when(tradingGroupMarkupService.find("vip", "BTCUSDT"))
                .thenReturn(Optional.of(new MarketGroupMarkupItem(
                        "vip", "BTCUSDT",
                        new BigDecimal("0.5"), new BigDecimal("1.0"),
                        true, T0)));

        long userId = 99001L;
        creditDeposit(userId, "stage12-pending-020");

        TradingPendingOrderTrigger order = tradingPendingOrderApplicationService.createOpening(
                userId, "vip", "BTCUSDT", TradingOrderSide.BUY,
                TradingPendingOrderType.LIMIT,
                new BigDecimal("1.00000000"),
                new BigDecimal("9000.00000000"),  // limit BUY trigger 价
                null,
                new BigDecimal("10"),
                TradingMarginMode.ISOLATED,
                "stage12-pending-020-clid");

        TradingPendingOrderTrigger reloaded = pendingOrderRepository.findById(order.id()).orElseThrow();
        Assertions.assertEquals("vip", reloaded.groupCodeAtCreate());
        Assertions.assertEquals(0, reloaded.bidExtraAtCreate().compareTo(new BigDecimal("0.5")),
                "bidExtraAtCreate 应冻结 0.5");
        Assertions.assertEquals(0, reloaded.askExtraAtCreate().compareTo(new BigDecimal("1.0")),
                "askExtraAtCreate 应冻结 1.0");
    }

    /** TC-GM-020b STOP 创建时同样冻结。 */
    @Test
    void TC_GM_020b_stop_create_freezes_group_markup() {
        Mockito.when(tradingGroupMarkupService.find("vip", "BTCUSDT"))
                .thenReturn(Optional.of(new MarketGroupMarkupItem(
                        "vip", "BTCUSDT",
                        new BigDecimal("0.3"), new BigDecimal("0.7"),
                        true, T0)));

        long userId = 99002L;
        creditDeposit(userId, "stage12-pending-020b");

        TradingPendingOrderTrigger order = tradingPendingOrderApplicationService.createOpening(
                userId, "vip", "BTCUSDT", TradingOrderSide.BUY,
                TradingPendingOrderType.STOP,
                new BigDecimal("1.00000000"),
                new BigDecimal("11000.00000000"),  // stop BUY trigger 价
                null,
                new BigDecimal("10"),
                TradingMarginMode.ISOLATED,
                "stage12-pending-020b-clid");

        TradingPendingOrderTrigger reloaded = pendingOrderRepository.findById(order.id()).orElseThrow();
        Assertions.assertEquals(0, reloaded.bidExtraAtCreate().compareTo(new BigDecimal("0.3")));
        Assertions.assertEquals(0, reloaded.askExtraAtCreate().compareTo(new BigDecimal("0.7")));
    }

    /** TC-GM-020c 缺 markup 配置（默认组）→ 冻结值都是 0，groupCodeAtCreate=default。 */
    @Test
    void TC_GM_020c_default_group_freezes_zero_markup() {
        long userId = 99003L;
        creditDeposit(userId, "stage12-pending-020c");

        TradingPendingOrderTrigger order = tradingPendingOrderApplicationService.createOpening(
                userId, null, "BTCUSDT", TradingOrderSide.BUY,
                TradingPendingOrderType.LIMIT,
                new BigDecimal("1.00000000"),
                new BigDecimal("9000.00000000"),
                null,
                new BigDecimal("10"),
                TradingMarginMode.ISOLATED,
                "stage12-pending-020c-clid");

        TradingPendingOrderTrigger reloaded = pendingOrderRepository.findById(order.id()).orElseThrow();
        Assertions.assertEquals("default", reloaded.groupCodeAtCreate());
        Assertions.assertEquals(0, reloaded.bidExtraAtCreate().compareTo(BigDecimal.ZERO));
        Assertions.assertEquals(0, reloaded.askExtraAtCreate().compareTo(BigDecimal.ZERO));
    }

    /**
     * TC-GM-022 触发判定按冻结值：管理端改 markup 后，已存在挂单仍按 frozen 值判定。
     * 通过直接调用 evaluator + 改 markup 后再次 evaluate 验证。
     */
    @Test
    void TC_GM_022_trigger_uses_frozen_markup_not_live_config() {
        Mockito.when(tradingGroupMarkupService.find("vip", "BTCUSDT"))
                .thenReturn(Optional.of(new MarketGroupMarkupItem(
                        "vip", "BTCUSDT",
                        new BigDecimal("0.5"), new BigDecimal("1.0"),
                        true, T0)));

        long userId = 99004L;
        creditDeposit(userId, "stage12-pending-022");

        // LIMIT BUY trigger=9000.5：原本想要 ask<=9000.5 触发
        // ask 9000，加 askExtra=1.0 → effectiveAsk=9001 > 9000.5 → 不触发
        // 如果 markup=0，effectiveAsk=9000 <= 9000.5 → 应触发
        // 测试关键：当下挂单 freeze 1.0；之后即使 service.find 返回 0/0，evaluator 仍用 1.0 → 不触发
        TradingPendingOrderTrigger order = tradingPendingOrderApplicationService.createOpening(
                userId, "vip", "BTCUSDT", TradingOrderSide.BUY,
                TradingPendingOrderType.LIMIT,
                new BigDecimal("1.00000000"),
                new BigDecimal("9000.50000000"),
                null, new BigDecimal("10"), TradingMarginMode.ISOLATED,
                "stage12-pending-022-clid");

        // "管理端把 markup 改回 0/0" 模拟
        Mockito.when(tradingGroupMarkupService.find("vip", "BTCUSDT"))
                .thenReturn(Optional.empty());

        // base quote ask=9000.0 触发判定
        TradingQuoteSnapshot quote = new TradingQuoteSnapshot(
                "BTCUSDT",
                new BigDecimal("8999.0"),  // bid
                new BigDecimal("9000.0"),  // ask
                new BigDecimal("8999.5"),
                OffsetDateTime.now(), "LP", false);

        TradingPendingOrderTrigger reloaded = pendingOrderRepository.findById(order.id()).orElseThrow();
        boolean triggered = pendingOrderTriggerEvaluator.evaluate(reloaded, quote);

        Assertions.assertFalse(triggered,
                "evaluator 用 frozen askExtraAtCreate=1.0：effectiveAsk=9001 > trigger 9000.5 → 不应触发；"
                        + "即使运营把 live markup 改成 0，存量挂单仍按冻结判定");
    }

    /** TC-GM-022b 反向：0 markup 挂单不受后续运营改 markup 影响（仍按 frozen 0 判定）。 */
    @Test
    void TC_GM_022b_default_group_trigger_unchanged_by_live_config_change() {
        long userId = 99005L;
        creditDeposit(userId, "stage12-pending-022b");

        // 创建时 vip find 返回空 → groupCode=null → default → frozen 0/0
        TradingPendingOrderTrigger order = tradingPendingOrderApplicationService.createOpening(
                userId, null, "BTCUSDT", TradingOrderSide.BUY,
                TradingPendingOrderType.LIMIT,
                new BigDecimal("1.00000000"),
                new BigDecimal("9000.50000000"),
                null, new BigDecimal("10"), TradingMarginMode.ISOLATED,
                "stage12-pending-022b-clid");

        // "运营改 default 组 markup 到 5/10" 模拟（不应影响该挂单）
        Mockito.when(tradingGroupMarkupService.find("default", "BTCUSDT"))
                .thenReturn(Optional.of(new MarketGroupMarkupItem(
                        "default", "BTCUSDT",
                        new BigDecimal("5"), new BigDecimal("10"),
                        true, T0.plusMinutes(5))));

        TradingQuoteSnapshot quote = new TradingQuoteSnapshot(
                "BTCUSDT",
                new BigDecimal("8999.0"),
                new BigDecimal("9000.0"),
                new BigDecimal("8999.5"),
                OffsetDateTime.now(), "LP", false);

        TradingPendingOrderTrigger reloaded = pendingOrderRepository.findById(order.id()).orElseThrow();
        boolean triggered = pendingOrderTriggerEvaluator.evaluate(reloaded, quote);

        // 按 frozen 0：effectiveAsk = 9000 <= 9000.5 → 应触发
        // 如果错误用 live 5/10：effectiveAsk = 9010 > 9000.5 → 不触发
        Assertions.assertTrue(triggered,
                "evaluator 应按 frozen=0 判定 → ask 9000<=trigger 9000.5 应触发；"
                        + "若错误用 live markup=10，effectiveAsk=9010 就不会触发");
    }

    private void creditDeposit(long userId, String txid) {
        tradingDepositCreditApplicationService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                "evt-" + txid,
                99000L + userId,
                userId,
                ChainType.ETH,
                "USDT",
                "0x" + txid,
                new BigDecimal("20000.00000000"),
                OffsetDateTime.now()
        ));
    }

    private void seedAlwaysOpenSchedule(String symbol, String marketCode) {
        tradingScheduleSnapshotRepository.saveForTest(new TradingScheduleSnapshot(
                symbol, marketCode,
                List.of(
                        new TradingSessionWindow(1, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(2, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(3, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(4, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(5, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(6, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(7, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null)
                ),
                List.of(),
                List.of(),
                OffsetDateTime.now()
        ));
    }
}
