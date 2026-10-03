package com.falconx.trading;

import com.falconx.domain.enums.ChainType;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingDepositCreditApplicationService;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.command.CreditConfirmedDepositCommand;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.dto.OrderPlacementResult;
import com.falconx.trading.dto.PriceTickProcessingResult;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.QuoteDrivenEngine;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingQuoteQualityStatus;
import com.falconx.trading.repository.RedisTradingScheduleSnapshotRepository;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import com.falconx.trading.service.model.TradingScheduleSnapshot;
import com.falconx.trading.service.model.TradingSessionWindow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
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

/**
 * STAGE-14C1 Task 9 集成测试：账户 MarginLevel 实时重算 + StopOut/liqPrice 双触发强平。
 *
 * <p>真 DB + 真引擎链路，验证 master §6.3 ISOLATED 双触发：
 * <ul>
 *   <li>MarginLevel ≤ 30% 但 liqPrice <b>未</b>命中 → 仍强平（MarginLevel 路径独立生效），
 *       并补发 STOP_OUT_TRIGGERED。</li>
 *   <li>仅 liqPrice 命中（MarginLevel 路径未触发）→ 强平但 <b>不</b>发 STOP_OUT_TRIGGERED（回归）。</li>
 * </ul>
 *
 * <p>BTCUSDT 计价币 = 账户币（USDT），FX=1，单仓 MarginLevel = (margin + uPnL) / (entry × mmRate) × 100。
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
class TradingMarginLevelStopOutIntegrationTests {

    @Autowired
    private TradingDepositCreditApplicationService tradingDepositCreditApplicationService;

    @Autowired
    private TradingOrderPlacementApplicationService tradingOrderPlacementApplicationService;

    @Autowired
    private QuoteDrivenEngine quoteDrivenEngine;

    @Autowired
    private TradingTestSupportMapper tradingTestSupportMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisTradingScheduleSnapshotRepository tradingScheduleSnapshotRepository;

    @Autowired
    private OpenPositionSnapshotStore openPositionSnapshotStore;

    @BeforeEach
    void cleanTradingStores() {
        tradingTestSupportMapper.clearOwnerTables();
        tradingTestSupportMapper.deleteNotification();
        // IT DB 默认无 BTCUSDT tier seed（Task 12 才做全量 Flyway tier seed）→ 此处补一条单档 tier
        // 避免下单被 TIER_CONFIG_NOT_FOUND 拒；crypto T1：maxLev=100, mmRate=0.01。
        tradingTestSupportMapper.seedSingleLeverageTier("BTCUSDT", "default", 100, "0.010000");
        openPositionSnapshotStore.replaceAll(List.of());
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:BTCUSDT");
        stringRedisTemplate.delete("falconx:market:trading:schedule:BTCUSDT");
    }

    @org.junit.jupiter.api.AfterEach
    void cleanupTierSeed() {
        // 不污染共享 IT DB 的 t_symbol_leverage_tier（clearOwnerTables 不清该表）：移除本类注入的 BTCUSDT 单档 tier。
        tradingTestSupportMapper.deleteLeverageTierBySymbolAndGroup("BTCUSDT", "default");
    }

    /**
     * MarginLevel ≤ stopOut(30%) → StopOut 触发强平 + 补发 STOP_OUT_TRIGGERED。
     *
     * <p><b>口径说明（关键发现）</b>：单仓 ISOLATED 下 liqPrice = entry×(1−1/lev+mmRate)，该点 equity_i = MM_i
     * → ML = 100%。故 ML≤30% 必然比 liqPrice 更深（mark 更低）；此 mark 下 liqPrice 也已命中，双触发同时成立，
     * 引擎按 stopOutTriggered=true 强平一次并补发 STOP_OUT_TRIGGERED。MarginLevel <b>独立于</b> liqPrice
     * 生效（liqPrice 未命中而 ML≤30%）的场景由单测
     * {@code QuoteDrivenEngineMarginLevelTriggerTests#shouldLiquidateWhenMarginLevelBreachesEvenIfLiqPriceNotHit}
     * 覆盖；在 ISOLATED 单仓 + 冻结 mmRate 一致口径下该场景物理不可达（详见汇报顾虑）。
     */
    @Test
    void shouldLiquidateAndSendStopOutWhenMarginLevelBreaches(CapturedOutput output) {
        long userId = 95101L;
        Long positionId = openPosition(userId, "stage14c1-stopout-001").position().positionId();

        BigDecimal entryPrice = new BigDecimal(tradingTestSupportMapper.selectPositionEntryPriceById(positionId));
        BigDecimal margin = new BigDecimal(tradingTestSupportMapper.selectPositionMarginById(positionId));
        BigDecimal mmRate = new BigDecimal(tradingTestSupportMapper.selectPositionMmRateAtOpenById(positionId));

        // 单仓 BUY qty=1：MM = entry × mmRate；ML = (margin + (mark-entry)) / MM × 100。
        // 取 ML = 25%（≤30% StopOut）解 mark：mark = entry - margin + 0.25 × MM（此价位 liqPrice 亦已命中，双触发）
        BigDecimal mm = entryPrice.multiply(mmRate);
        BigDecimal targetMark = entryPrice.subtract(margin).add(mm.multiply(new BigDecimal("0.25")));

        PriceTickProcessingResult result = publishQuote(
                "BTCUSDT",
                targetMark.subtract(new BigDecimal("1.00000000")),
                targetMark.add(new BigDecimal("1.00000000")),
                targetMark,
                OffsetDateTime.now());

        // 双触发同时命中 → 强平一次（不重复）
        Assertions.assertEquals(1, result.triggeredActions());
        Assertions.assertEquals(3, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals(4, tradingTestSupportMapper.selectPositionCloseReasonCodeById(positionId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countLiquidationLogsByPositionId(positionId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.liquidation.executed"));
        Assertions.assertTrue(openPositionSnapshotStore.listOpenByUserId(userId).isEmpty());
        // StopOut 路径补发 STOP_OUT_TRIGGERED 通知（恰一条）
        Assertions.assertEquals(1,
                tradingTestSupportMapper.countNotificationByUserIdAndType(userId, "STOP_OUT_TRIGGERED"));
        Assertions.assertTrue(output.toString().contains("trading.margin.stop-out.triggered"));
        Assertions.assertTrue(output.toString().contains("trading.liquidation.triggered"));
    }

    @Test
    void shouldNotSendStopOutNotificationWhenLiquidationDrivenByLiqPriceOnly() {
        long userId = 95102L;
        OrderPlacementResult placed = openPosition(userId, "stage14c1-stopout-002");
        Assertions.assertNotNull(placed.position(), "下单被拒: " + placed.rejectionReason());
        Long positionId = placed.position().positionId();
        BigDecimal liqPrice = new BigDecimal(tradingTestSupportMapper.selectPositionLiquidationPriceById(positionId));

        // 在 liqPrice 处命中（此点 ML = 100%，StopOut 未触发）→ 仅 liqPrice 路径强平，
        // 不发 STOP_OUT_TRIGGERED（POSITION_LIQUIDATED 由 close 服务 afterCommit 发，不在本断言范围）。
        PriceTickProcessingResult result = publishQuote(
                "BTCUSDT",
                liqPrice.subtract(new BigDecimal("0.50000000")),
                liqPrice.add(new BigDecimal("0.50000000")),
                liqPrice,
                OffsetDateTime.now());

        Assertions.assertEquals(1, result.triggeredActions());
        Assertions.assertEquals(3, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        // 同一 tick 同一仓位只强平一次
        Assertions.assertEquals(1, tradingTestSupportMapper.countLiquidationLogsByPositionId(positionId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.liquidation.executed"));
        // liqPrice 路径（ML=100%，非 StopOut）不补发 STOP_OUT_TRIGGERED
        Assertions.assertEquals(0,
                tradingTestSupportMapper.countNotificationByUserIdAndType(userId, "STOP_OUT_TRIGGERED"));
    }

    private OrderPlacementResult openPosition(long userId, String clientOrderId) {
        OffsetDateTime now = OffsetDateTime.now();
        tradingDepositCreditApplicationService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                "evt-" + clientOrderId,
                99000L + userId,
                userId,
                ChainType.ETH,
                "USDT",
                "0x" + clientOrderId,
                new BigDecimal("2000.00000000"),
                now));
        seedAlwaysOpenSchedule("BTCUSDT", "CRYPTO");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                now);
        // 无 SL（避免 SL 抢先触发），TP 设很高不命中
        return tradingOrderPlacementApplicationService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId,
                "BTCUSDT",
                TradingOrderSide.BUY,
                new BigDecimal("1.00000000"),
                new BigDecimal("10"),
                null,
                new BigDecimal("99999.00000000"),
                null,
                clientOrderId));
    }

    private PriceTickProcessingResult publishQuote(String symbol,
                                                   BigDecimal bid,
                                                   BigDecimal ask,
                                                   BigDecimal mark,
                                                   OffsetDateTime ts) {
        return quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                symbol, bid, ask, mark, mark, ts, "stage14c1-stopout-it",
                false, TradingQuoteQualityStatus.FRESH.name(), null));
    }

    private void seedAlwaysOpenSchedule(String symbol, String marketCode) {
        tradingScheduleSnapshotRepository.saveForTest(new TradingScheduleSnapshot(
                symbol,
                marketCode,
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
                OffsetDateTime.now()));
    }
}
