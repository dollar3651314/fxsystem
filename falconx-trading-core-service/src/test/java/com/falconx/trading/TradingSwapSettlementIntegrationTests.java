package com.falconx.trading;

import com.falconx.domain.enums.ChainType;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingDepositCreditApplicationService;
import com.falconx.trading.command.CreditConfirmedDepositCommand;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.dto.OrderPlacementResult;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.QuoteDrivenEngine;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.repository.RedisTradingScheduleSnapshotRepository;
import com.falconx.trading.repository.RedisTradingSwapRateSnapshotRepository;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import com.falconx.trading.service.TradingSwapSettlementService;
import com.falconx.trading.service.model.TradingScheduleSnapshot;
import com.falconx.trading.service.model.TradingSessionWindow;
import com.falconx.trading.service.model.TradingSwapRateRule;
import com.falconx.trading.service.model.TradingSwapRateSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
 * trading-core-service 隔夜利息结算集成测试。
 *
 * <p>该测试直接验证 `Swap rate` 共享快照和本地结算链路：
 *
 * <ol>
 *   <li>market owner 快照通过 Redis 提供正式费率来源</li>
 *   <li>trading-core 扫描 OPEN 持仓并按 rollover 时点 fresh 价格结算</li>
 *   <li>`t_ledger.biz_type=6/7`、`t_account.balance` 与幂等语义保持一致</li>
 * </ol>
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = TradingCoreServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_swap_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380",
                "falconx.trading.kafka.market-price-tick-topic=trading.swap.it.market.price.tick.${random.uuid}",
                "falconx.trading.kafka.market-kline-update-topic=trading.swap.it.market.kline.update.${random.uuid}",
                "falconx.trading.kafka.wallet-deposit-confirmed-topic=trading.swap.it.wallet.deposit.confirmed.${random.uuid}",
                "falconx.trading.kafka.wallet-deposit-reversed-topic=trading.swap.it.wallet.deposit.reversed.${random.uuid}",
                "falconx.trading.kafka.consumer-group-id=trading-swap-it-${random.uuid}",
                "falconx.trading.stale.max-age=2m",
                "falconx.trading.swap.enabled=false"
        }
)
@ExtendWith(OutputCaptureExtension.class)
class TradingSwapSettlementIntegrationTests {

    @Autowired
    private TradingDepositCreditApplicationService tradingDepositCreditApplicationService;

    @Autowired
    private com.falconx.trading.application.TradingOrderPlacementApplicationService tradingOrderPlacementApplicationService;

    @Autowired
    private TradingSwapSettlementService tradingSwapSettlementService;

    @Autowired
    private QuoteDrivenEngine quoteDrivenEngine;

    @Autowired
    private TradingTestSupportMapper tradingTestSupportMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisTradingScheduleSnapshotRepository tradingScheduleSnapshotRepository;

    @Autowired
    private RedisTradingSwapRateSnapshotRepository tradingSwapRateSnapshotRepository;

    @Autowired
    private OpenPositionSnapshotStore openPositionSnapshotStore;

    @BeforeEach
    void cleanTradingStores() {
        tradingTestSupportMapper.clearOwnerTables();
        openPositionSnapshotStore.replaceAll(List.of());
        stringRedisTemplate.delete("falconx:trading:quote:snapshot:BTCUSDT");
        stringRedisTemplate.delete("falconx:market:trading:schedule:BTCUSDT");
        stringRedisTemplate.delete("falconx:market:swap-rate:BTCUSDT");
    }

    @Test
    void shouldChargeLongPositionSwapAtDueRollover() {
        long userId = 93101L;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime rolloverAt = now.minusSeconds(30);
        seedAlwaysOpenSchedule("BTCUSDT", "CRYPTO");
        seedSwapRates(
                "BTCUSDT",
                new TradingSwapRateRule(now.toLocalDate().minusDays(1), rolloverAt.toLocalTime().withNano(0), new BigDecimal("-0.00010000"), new BigDecimal("0.00010000"))
        );
        openPosition(userId, TradingOrderSide.BUY, "swap-long-001", rolloverAt.minusHours(2));
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10000.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("10000.00000000"),
                now.minusSeconds(5)
        );

        int settled = tradingSwapSettlementService.settleDuePositions(now);

        Assertions.assertEquals(1, settled);
        Assertions.assertEquals(1, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 6));
        Assertions.assertEquals("1.00000000", tradingTestSupportMapper.selectLatestLedgerAmountByUserIdAndBizType(userId, 6));
        Assertions.assertEquals("1994.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        Assertions.assertEquals("1994.00000000", tradingTestSupportMapper.selectLatestLedgerBalanceSnapshotByUserIdAndBizType(userId, 6));
        Assertions.assertEquals("1000.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertEquals("1000.00000000", tradingTestSupportMapper.selectLatestLedgerMarginUsedSnapshotByUserIdAndBizType(userId, 6));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
    }

    @Test
    void shouldCreditShortPositionSwapIncomeAndRemainIdempotent(CapturedOutput output) {
        long userId = 93102L;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime rolloverAt = now.minusSeconds(30);
        seedAlwaysOpenSchedule("BTCUSDT", "CRYPTO");
        seedSwapRates(
                "BTCUSDT",
                new TradingSwapRateRule(now.toLocalDate().minusDays(1), rolloverAt.toLocalTime().withNano(0), new BigDecimal("-0.00010000"), new BigDecimal("0.00010000"))
        );
        OrderPlacementResult result = openPosition(userId, TradingOrderSide.SELL, "swap-short-001", rolloverAt.minusHours(2));
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10000.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("10000.00000000"),
                now.minusSeconds(5)
        );

        String recordMarker = "\"positionId\":" + result.position().positionId();
        int firstSettled;
        int secondSettled;
        try (KafkaConsumer<String, String> consumer = createConsumer("falconx.trading.swap.settled")) {
            firstSettled = tradingSwapSettlementService.settleDuePositions(now);
            waitForTopicRecord(consumer, recordMarker);
            secondSettled = tradingSwapSettlementService.settleDuePositions(OffsetDateTime.now(ZoneOffset.UTC));
            assertNoAdditionalTopicRecord(consumer, recordMarker, Duration.ofSeconds(2));
        }

        Assertions.assertEquals(1, firstSettled);
        Assertions.assertEquals(0, secondSettled);
        Assertions.assertEquals(1, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 7));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.swap.settled"));
        Assertions.assertEquals("1.00000000", tradingTestSupportMapper.selectLatestLedgerAmountByUserIdAndBizType(userId, 7));
        Assertions.assertEquals("1996.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
        Assertions.assertTrue(output.toString().contains("trading.swap.settlement.completed"));
        Assertions.assertTrue(output.toString().contains("trading.swap.settlement.duplicate"));
        Assertions.assertTrue(output.toString().contains("trading.swap.settlement.batch.completed"));
        Assertions.assertTrue(output.toString().contains("skippedAlreadySettled=1"));
    }

    @Test
    void shouldSkipStaleQuoteAndRetryAfterFreshQuoteArrives() {
        long userId = 93103L;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime rolloverAt = now.minusSeconds(30);
        seedAlwaysOpenSchedule("BTCUSDT", "CRYPTO");
        seedSwapRates(
                "BTCUSDT",
                new TradingSwapRateRule(now.toLocalDate().minusDays(1), rolloverAt.toLocalTime().withNano(0), new BigDecimal("-0.00010000"), new BigDecimal("0.00010000"))
        );
        openPosition(userId, TradingOrderSide.BUY, "swap-stale-001", rolloverAt.minusHours(2));
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10000.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("10000.00000000"),
                now.minusMinutes(3)
        );

        int staleSettled = tradingSwapSettlementService.settleDuePositions(now);

        Assertions.assertEquals(0, staleSettled);
        Assertions.assertEquals(0, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 6));
        Assertions.assertEquals("1995.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));

        publishQuote(
                "BTCUSDT",
                new BigDecimal("10000.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("10000.00000000"),
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5)
        );

        int retriedSettled = tradingSwapSettlementService.settleDuePositions(OffsetDateTime.now(ZoneOffset.UTC));

        Assertions.assertEquals(1, retriedSettled);
        Assertions.assertEquals(1, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 6));
        Assertions.assertEquals("1994.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
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

    private void seedSwapRates(String symbol, TradingSwapRateRule... rules) {
        tradingSwapRateSnapshotRepository.saveForTest(new TradingSwapRateSnapshot(
                symbol,
                List.of(rules),
                OffsetDateTime.now()
        ));
    }

    private void publishQuote(String symbol,
                              BigDecimal bid,
                              BigDecimal ask,
                              BigDecimal mark,
                              OffsetDateTime ts) {
        quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                symbol,
                bid,
                ask,
                mark,
                mark,
                ts,
                "swap-it",
                false
        ));
    }

    private OrderPlacementResult openPosition(long userId,
                                              TradingOrderSide side,
                                              String clientOrderId,
                                              OffsetDateTime targetOpenedAt) {
        OffsetDateTime placementTime = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5);
        tradingDepositCreditApplicationService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                "evt-" + clientOrderId,
                99600L + userId,
                userId,
                ChainType.ETH,
                "USDT",
                "0x" + clientOrderId,
                new BigDecimal("2000.00000000"),
                placementTime
        ));
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10000.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("10000.00000000"),
                placementTime
        );
        OrderPlacementResult result = tradingOrderPlacementApplicationService.placeMarketOrder(new PlaceMarketOrderCommand(
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
        Assertions.assertNotNull(result.position());
        tradingTestSupportMapper.updatePositionOpenedAt(
                result.position().positionId(),
                toUtcLocalDateTime(targetOpenedAt)
        );
        return result;
    }

    private LocalDateTime toUtcLocalDateTime(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private KafkaConsumer<String, String> createConsumer(String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "trading-swap-it-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(List.of(topic));
        consumer.poll(Duration.ofMillis(200));
        return consumer;
    }

    private void waitForTopicRecord(KafkaConsumer<String, String> consumer, String marker) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (record.value() != null && record.value().contains(marker)) {
                    return;
                }
            }
        }
        Assertions.fail("Did not receive trading.swap.settled message, marker=" + marker);
    }

    private void assertNoAdditionalTopicRecord(KafkaConsumer<String, String> consumer, String marker, Duration duration) {
        long deadline = System.currentTimeMillis() + duration.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                if (record.value() != null && record.value().contains(marker)) {
                    Assertions.fail("Received unexpected duplicate trading.swap.settled message, marker=" + marker);
                }
            }
        }
    }
}
