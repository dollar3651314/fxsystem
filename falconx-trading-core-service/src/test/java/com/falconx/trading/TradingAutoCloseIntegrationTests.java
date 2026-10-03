package com.falconx.trading;

import com.falconx.domain.enums.ChainType;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingDepositCreditApplicationService;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.command.CreditConfirmedDepositCommand;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.dto.OrderPlacementResult;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * TP / SL 自动平仓集成测试。
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
class TradingAutoCloseIntegrationTests {

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
        openPositionSnapshotStore.replaceAll(List.of());
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:BTCUSDT");
        stringRedisTemplate.delete("falconx:market:trading:schedule:BTCUSDT");
    }

    @Test
    void shouldAutoCloseLongPositionWhenTakeProfitIsTriggered() {
        long userId = 93001L;
        Long positionId = openPosition(userId, TradingOrderSide.BUY, "stage7-tp-001").position().positionId();

        var result = publishQuote(
                "BTCUSDT",
                new BigDecimal("10100.00000000"),
                new BigDecimal("10110.00000000"),
                new BigDecimal("10105.00000000"),
                OffsetDateTime.now()
        );

        Assertions.assertEquals(1, result.triggeredActions());
        Assertions.assertEquals(2, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals(2, tradingTestSupportMapper.selectPositionCloseReasonCodeById(positionId));
        Assertions.assertEquals("10100.00000000", tradingTestSupportMapper.selectPositionClosePriceById(positionId));
        Assertions.assertEquals("100.00000000", tradingTestSupportMapper.selectPositionRealizedPnlById(positionId));
        Assertions.assertEquals("10100.00000000", tradingTestSupportMapper.selectTradePriceByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals("100.00000000", tradingTestSupportMapper.selectTradeRealizedPnlByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectTradeFeeByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals("100.00000000", tradingTestSupportMapper.selectLatestLedgerAmountByUserIdAndBizType(userId, 8));
        Assertions.assertEquals("2095.00000000", tradingTestSupportMapper.selectLatestLedgerBalanceSnapshotByUserIdAndBizType(userId, 8));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectLatestLedgerMarginUsedSnapshotByUserIdAndBizType(userId, 8));
        Assertions.assertEquals("2095.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectRiskExposureTotalLongQtyBySymbol("BTCUSDT"));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectRiskExposureTotalShortQtyBySymbol("BTCUSDT"));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectRiskExposureNetBySymbol("BTCUSDT"));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
        Assertions.assertTrue(openPositionSnapshotStore.listOpenByUserId(userId).isEmpty());
    }

    @Test
    void shouldAutoCloseLongPositionWhenStopLossIsTriggered() {
        long userId = 93002L;
        Long positionId = openPosition(userId, TradingOrderSide.BUY, "stage7-sl-001").position().positionId();

        var result = publishQuote(
                "BTCUSDT",
                new BigDecimal("9795.00000000"),
                new BigDecimal("9805.00000000"),
                new BigDecimal("9800.00000000"),
                OffsetDateTime.now()
        );

        Assertions.assertEquals(1, result.triggeredActions());
        Assertions.assertEquals(2, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals(3, tradingTestSupportMapper.selectPositionCloseReasonCodeById(positionId));
        Assertions.assertEquals("9795.00000000", tradingTestSupportMapper.selectPositionClosePriceById(positionId));
        Assertions.assertEquals("-205.00000000", tradingTestSupportMapper.selectPositionRealizedPnlById(positionId));
        Assertions.assertEquals("-205.00000000", tradingTestSupportMapper.selectLatestLedgerAmountByUserIdAndBizType(userId, 8));
        Assertions.assertEquals("1790.00000000", tradingTestSupportMapper.selectLatestLedgerBalanceSnapshotByUserIdAndBizType(userId, 8));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectLatestLedgerMarginUsedSnapshotByUserIdAndBizType(userId, 8));
        Assertions.assertEquals("1790.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectRiskExposureNetBySymbol("BTCUSDT"));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
        Assertions.assertTrue(openPositionSnapshotStore.listOpenByUserId(userId).isEmpty());
    }

    @Test
    void shouldNotTriggerCloseTwiceWhenDuplicatePriceTickArrives() {
        long userId = 93003L;
        Long positionId = openPosition(userId, TradingOrderSide.BUY, "stage7-tp-duplicate-001").position().positionId();

        var firstResult = publishQuote(
                "BTCUSDT",
                new BigDecimal("10100.00000000"),
                new BigDecimal("10110.00000000"),
                new BigDecimal("10105.00000000"),
                OffsetDateTime.now()
        );
        var secondResult = publishQuote(
                "BTCUSDT",
                new BigDecimal("10100.00000000"),
                new BigDecimal("10110.00000000"),
                new BigDecimal("10105.00000000"),
                OffsetDateTime.now().plusSeconds(1)
        );

        Assertions.assertEquals(1, firstResult.triggeredActions());
        Assertions.assertEquals(0, secondResult.triggeredActions());
        Assertions.assertEquals(2, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals(1, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 8));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
        Assertions.assertTrue(openPositionSnapshotStore.listOpenByUserId(userId).isEmpty());
    }

    @Test
    void shouldNotTriggerTakeProfitWhenQuoteQualityIsNoQuote() {
        long userId = 93004L;
        Long positionId = openPosition(userId, TradingOrderSide.BUY, "stage7-tp-no-quote-001").position().positionId();

        var result = publishQuote(
                "BTCUSDT",
                new BigDecimal("10100.00000000"),
                new BigDecimal("10110.00000000"),
                new BigDecimal("10105.00000000"),
                OffsetDateTime.now(),
                TradingQuoteQualityStatus.NO_QUOTE,
                "UNCHANGED_TOO_LONG"
        );

        Assertions.assertEquals(0, result.triggeredActions());
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals(1, openPositionSnapshotStore.listOpenByUserId(userId).size());
    }

    @Test
    void shouldNotTriggerStopLossWhenQuoteTimestampDriftsTooFarInFuture() {
        long userId = 93005L;
        Long positionId = openPosition(userId, TradingOrderSide.BUY, "stage7-sl-future-001").position().positionId();

        var result = publishQuote(
                "BTCUSDT",
                new BigDecimal("9795.00000000"),
                new BigDecimal("9805.00000000"),
                new BigDecimal("9800.00000000"),
                OffsetDateTime.now().plusSeconds(10)
        );

        Assertions.assertEquals(0, result.triggeredActions());
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals(1, openPositionSnapshotStore.listOpenByUserId(userId).size());
    }

    private OrderPlacementResult openPosition(long userId, TradingOrderSide side, String clientOrderId) {
        OffsetDateTime now = OffsetDateTime.now();
        tradingDepositCreditApplicationService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                "evt-" + clientOrderId,
                98000L + userId,
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
                side,
                new BigDecimal("1.00000000"),
                new BigDecimal("10"),
                null,
                side == TradingOrderSide.BUY ? new BigDecimal("10100.00000000") : new BigDecimal("9800.00000000"),
                side == TradingOrderSide.BUY ? new BigDecimal("9800.00000000") : new BigDecimal("10100.00000000"),
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
                "stage7-auto-close-it",
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
}
