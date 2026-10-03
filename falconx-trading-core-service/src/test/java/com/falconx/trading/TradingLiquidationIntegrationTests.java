package com.falconx.trading;

import com.falconx.domain.enums.ChainType;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingDepositCreditApplicationService;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.application.TradingPositionCloseApplicationService;
import com.falconx.trading.application.TradingPositionMarginApplicationService;
import com.falconx.trading.command.AddIsolatedMarginCommand;
import com.falconx.trading.command.CreditConfirmedDepositCommand;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.dto.OrderPlacementResult;
import com.falconx.trading.dto.PositionCloseResult;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.QuoteDrivenEngine;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPositionCloseReason;
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
 * 强平与负净值保护集成测试。
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
class TradingLiquidationIntegrationTests {

    @Autowired
    private TradingDepositCreditApplicationService tradingDepositCreditApplicationService;

    @Autowired
    private TradingOrderPlacementApplicationService tradingOrderPlacementApplicationService;

    @Autowired
    private TradingPositionMarginApplicationService tradingPositionMarginApplicationService;

    @Autowired
    private TradingPositionCloseApplicationService tradingPositionCloseApplicationService;

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
        openPositionSnapshotStore.replaceAll(List.of());
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:BTCUSDT");
        stringRedisTemplate.delete("falconx:market:trading:schedule:BTCUSDT");
    }

    @Test
    void shouldLiquidatePositionWhenMaintenanceMarginIsBreached(CapturedOutput output) {
        long userId = 94001L;
        Long positionId = openPosition(userId, "stage7-liquidation-001").position().positionId();
        BigDecimal liquidationPrice = new BigDecimal(tradingTestSupportMapper.selectPositionLiquidationPriceById(positionId));

        var result = publishQuote(
                "BTCUSDT",
                liquidationPrice.subtract(new BigDecimal("5.00000000")),
                liquidationPrice.add(new BigDecimal("5.00000000")),
                liquidationPrice,
                OffsetDateTime.now()
        );
        BigDecimal expectedClosePrice = liquidationPrice.subtract(new BigDecimal("5.00000000"));

        Assertions.assertEquals(1, result.triggeredActions());
        Assertions.assertEquals(3, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals(4, tradingTestSupportMapper.selectPositionCloseReasonCodeById(positionId));
        Assertions.assertEquals(expectedClosePrice.toPlainString(), tradingTestSupportMapper.selectPositionClosePriceById(positionId));
        Assertions.assertEquals(expectedClosePrice.toPlainString(), tradingTestSupportMapper.selectTradePriceByPositionIdAndTradeType(positionId, 3));
        Assertions.assertEquals("-955.00000000", tradingTestSupportMapper.selectTradeRealizedPnlByPositionIdAndTradeType(positionId, 3));
        Assertions.assertEquals("1040.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertEquals("-955.00000000", tradingTestSupportMapper.selectLatestLedgerAmountByUserIdAndBizType(userId, 9));
        Assertions.assertEquals("1040.00000000", tradingTestSupportMapper.selectLatestLedgerBalanceSnapshotByUserIdAndBizType(userId, 9));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectLatestLedgerMarginUsedSnapshotByUserIdAndBizType(userId, 9));
        Assertions.assertEquals(1, tradingTestSupportMapper.countLiquidationLogsByPositionId(positionId));
        Assertions.assertEquals("2", tradingTestSupportMapper.selectLiquidationLogMarginModeByPositionId(positionId));
        Assertions.assertEquals(expectedClosePrice.toPlainString(), tradingTestSupportMapper.selectLiquidationLogPriceByPositionId(positionId));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectLiquidationLogPlatformCoveredLossByPositionId(positionId));
        Assertions.assertEquals("1000.00000000", tradingTestSupportMapper.selectLiquidationLogMarginReleasedByPositionId(positionId));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectRiskExposureTotalLongQtyBySymbol("BTCUSDT"));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectRiskExposureNetBySymbol("BTCUSDT"));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.liquidation.executed"));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
        Assertions.assertTrue(openPositionSnapshotStore.listOpenByUserId(userId).isEmpty());
        Assertions.assertTrue(output.toString().contains("trading.liquidation.triggered"));
        Assertions.assertTrue(output.toString().contains("trading.liquidation.executed"));
    }

    @Test
    void shouldProtectAccountBalanceFromGoingNegativeDuringLiquidation() {
        long userId = 94002L;
        Long positionId = openPosition(userId, "stage7-liquidation-002").position().positionId();

        var result = publishQuote(
                "BTCUSDT",
                new BigDecimal("4995.00000000"),
                new BigDecimal("5005.00000000"),
                new BigDecimal("5000.00000000"),
                OffsetDateTime.now()
        );

        Assertions.assertEquals(1, result.triggeredActions());
        Assertions.assertEquals(3, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals("-5005.00000000", tradingTestSupportMapper.selectPositionRealizedPnlById(positionId));
        Assertions.assertEquals("-5005.00000000", tradingTestSupportMapper.selectTradeRealizedPnlByPositionIdAndTradeType(positionId, 3));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertEquals("-1995.00000000", tradingTestSupportMapper.selectLatestLedgerAmountByUserIdAndBizType(userId, 9));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectLatestLedgerBalanceSnapshotByUserIdAndBizType(userId, 9));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectLatestLedgerMarginUsedSnapshotByUserIdAndBizType(userId, 9));
        Assertions.assertEquals(1, tradingTestSupportMapper.countLiquidationLogsByPositionId(positionId));
        Assertions.assertEquals("3010.00000000", tradingTestSupportMapper.selectLiquidationLogPlatformCoveredLossByPositionId(positionId));
        Assertions.assertTrue(new BigDecimal(tradingTestSupportMapper.selectAccountBalanceByUserId(userId)).compareTo(BigDecimal.ZERO) >= 0);
        Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.liquidation.executed"));
        Assertions.assertTrue(openPositionSnapshotStore.listOpenByUserId(userId).isEmpty());
    }

    @Test
    void shouldNotLiquidateWhenTradingHoursBlockQuoteExecution() {
        long userId = 94004L;
        Long positionId = openPosition(userId, "stage7-liquidation-004").position().positionId();
        seedHolidayClosedSchedule("BTCUSDT", "CRYPTO");
        BigDecimal liquidationPrice = new BigDecimal(tradingTestSupportMapper.selectPositionLiquidationPriceById(positionId));

        var result = publishQuote(
                "BTCUSDT",
                liquidationPrice.subtract(new BigDecimal("5.00000000")),
                liquidationPrice.add(new BigDecimal("5.00000000")),
                liquidationPrice.subtract(new BigDecimal("1.00000000")),
                OffsetDateTime.now()
        );

        Assertions.assertEquals(0, result.triggeredActions());
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLiquidationLogsByPositionId(positionId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.liquidation.executed"));
        Assertions.assertEquals(1, openPositionSnapshotStore.listOpenByUserId(userId).size());
    }

    @Test
    void shouldNotLiquidateWhenQuoteQualityIsNoQuote() {
        long userId = 94006L;
        Long positionId = openPosition(userId, "stage7-liquidation-006").position().positionId();
        BigDecimal liquidationPrice = new BigDecimal(tradingTestSupportMapper.selectPositionLiquidationPriceById(positionId));

        var result = publishQuote(
                "BTCUSDT",
                liquidationPrice.subtract(new BigDecimal("5.00000000")),
                liquidationPrice.add(new BigDecimal("5.00000000")),
                liquidationPrice,
                OffsetDateTime.now(),
                TradingQuoteQualityStatus.NO_QUOTE,
                "UNCHANGED_TOO_LONG"
        );

        Assertions.assertEquals(0, result.triggeredActions());
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLiquidationLogsByPositionId(positionId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.liquidation.executed"));
        Assertions.assertEquals(1, openPositionSnapshotStore.listOpenByUserId(userId).size());
    }

    @Test
    void shouldRollbackLiquidationWhenRiskExposureUpdateFails() {
        long userId = 94003L;
        Long positionId = openPosition(userId, "stage7-liquidation-003").position().positionId();
        BigDecimal liquidationPrice = new BigDecimal(tradingTestSupportMapper.selectPositionLiquidationPriceById(positionId));
        tradingTestSupportMapper.deleteRiskExposureBySymbol("BTCUSDT");

        IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () -> publishQuote(
                "BTCUSDT",
                liquidationPrice.subtract(new BigDecimal("5.00000000")),
                liquidationPrice.add(new BigDecimal("5.00000000")),
                liquidationPrice,
                OffsetDateTime.now()
        ));

        Assertions.assertTrue(exception.getMessage().contains("Risk exposure"));
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertNull(tradingTestSupportMapper.selectPositionClosePriceById(positionId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(positionId, 3));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLiquidationLogsByPositionId(positionId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.liquidation.executed"));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 9));
        Assertions.assertEquals("1995.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        Assertions.assertEquals("1000.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertEquals(1, openPositionSnapshotStore.listOpenByUserId(userId).size());
        Assertions.assertEquals(positionId, openPositionSnapshotStore.listOpenByUserId(userId).getFirst().positionId());
    }

    @Test
    void shouldRecalculateLiquidationPriceAfterSupplementAndSkipOldTrigger() {
        long userId = 94005L;
        Long positionId = openPositionWithoutRiskControls(userId, "stage7a-liquidation-005").position().positionId();
        BigDecimal oldLiquidationPrice = new BigDecimal(tradingTestSupportMapper.selectPositionLiquidationPriceById(positionId));

        tradingPositionMarginApplicationService.addIsolatedMargin(new AddIsolatedMarginCommand(
                userId,
                positionId,
                new BigDecimal("200.00000000")
        ));

        BigDecimal newLiquidationPrice = new BigDecimal(tradingTestSupportMapper.selectPositionLiquidationPriceById(positionId));
        Assertions.assertEquals(0, new BigDecimal("1200.00000000").compareTo(new BigDecimal(tradingTestSupportMapper.selectPositionMarginById(positionId))));
        Assertions.assertEquals(0, new BigDecimal("1200.00000000").compareTo(new BigDecimal(tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId))));
        Assertions.assertTrue(newLiquidationPrice.compareTo(oldLiquidationPrice) < 0);
        Assertions.assertEquals(0, new BigDecimal("8850.00000000").compareTo(newLiquidationPrice));
        Assertions.assertEquals(0, newLiquidationPrice.compareTo(openPositionSnapshotStore.listOpenByUserId(userId).getFirst().liquidationPrice()));

        PositionCloseResult skipped = tradingPositionCloseApplicationService.closePositionByTrigger(
                positionId,
                TradingPositionCloseReason.LIQUIDATION,
                new com.falconx.trading.entity.TradingQuoteSnapshot(
                        "BTCUSDT",
                        oldLiquidationPrice.subtract(new BigDecimal("5.00000000")),
                        oldLiquidationPrice.add(new BigDecimal("5.00000000")),
                        oldLiquidationPrice,
                        OffsetDateTime.now(),
                        "stage7a-liquidation-revalidate",
                        false
                )
        );
        Assertions.assertNull(skipped);
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));

        var newTriggerResult = publishQuote(
                "BTCUSDT",
                newLiquidationPrice.subtract(new BigDecimal("5.00000000")),
                newLiquidationPrice.add(new BigDecimal("5.00000000")),
                newLiquidationPrice,
                OffsetDateTime.now()
        );
        Assertions.assertEquals(1, newTriggerResult.triggeredActions());
        Assertions.assertEquals(3, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals("840.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertEquals("-1155.00000000", tradingTestSupportMapper.selectLatestLedgerAmountByUserIdAndBizType(userId, 9));
    }

    private OrderPlacementResult openPosition(long userId, String clientOrderId) {
        return openPosition(userId, clientOrderId, new BigDecimal("10100.00000000"), new BigDecimal("9800.00000000"));
    }

    private OrderPlacementResult openPositionWithoutRiskControls(long userId, String clientOrderId) {
        return openPosition(userId, clientOrderId, null, null);
    }

    private OrderPlacementResult openPosition(long userId,
                                              String clientOrderId,
                                              BigDecimal takeProfitPrice,
                                              BigDecimal stopLossPrice) {
        OffsetDateTime now = OffsetDateTime.now();
        tradingDepositCreditApplicationService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                "evt-" + clientOrderId,
                99000L + userId,
                userId,
                ChainType.ETH,
                "USDT",
                "0x" + clientOrderId,
                new BigDecimal("2000.00000000"),
                now
        ));
        seedAlwaysOpenSchedule("BTCUSDT", "CRYPTO");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                now
        );
        return tradingOrderPlacementApplicationService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId,
                "BTCUSDT",
                TradingOrderSide.BUY,
                new BigDecimal("1.00000000"),
                new BigDecimal("10"),
                null,
                takeProfitPrice,
                stopLossPrice,
                clientOrderId
        ));
    }

    private com.falconx.trading.dto.PriceTickProcessingResult publishQuote(String symbol,
                                                                           BigDecimal bid,
                                                                           BigDecimal ask,
                                                                           BigDecimal mark,
                                                                           OffsetDateTime ts) {
        return publishQuote(symbol, bid, ask, mark, ts, TradingQuoteQualityStatus.FRESH, null);
    }

    private com.falconx.trading.dto.PriceTickProcessingResult publishQuote(String symbol,
                                                                           BigDecimal bid,
                                                                           BigDecimal ask,
                                                                           BigDecimal mark,
                                                                           OffsetDateTime ts,
                                                                           TradingQuoteQualityStatus qualityStatus,
                                                                           String qualityReason) {
        return quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                symbol,
                bid,
                ask,
                mark,
                mark,
                ts,
                "stage7-liquidation-it",
                qualityStatus.stale(),
                qualityStatus.name(),
                qualityReason
        ));
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
                OffsetDateTime.now()
        ));
    }

    private void seedHolidayClosedSchedule(String symbol, String marketCode) {
        tradingScheduleSnapshotRepository.saveForTest(new TradingScheduleSnapshot(
                symbol,
                marketCode,
                List.of(
                        new TradingSessionWindow(1, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null),
                        new TradingSessionWindow(2, 1, LocalTime.of(0, 0), LocalTime.of(23, 59, 59), "UTC", true, LocalDate.of(2026, 1, 1), null)
                ),
                List.of(),
                List.of(new com.falconx.trading.service.model.TradingHolidayRule(
                        marketCode,
                        OffsetDateTime.now(java.time.ZoneOffset.UTC).toLocalDate(),
                        1,
                        null,
                        null,
                        "UTC",
                        "Integration Holiday",
                        "INT"
                )),
                OffsetDateTime.now()
        ));
    }
}
