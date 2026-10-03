package com.falconx.trading;

import com.falconx.domain.enums.ChainType;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.TradingDepositCreditApplicationService;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.application.TradingPositionCloseApplicationService;
import com.falconx.trading.command.CloseTradingPositionCommand;
import com.falconx.trading.command.CreditConfirmedDepositCommand;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.dto.OrderPlacementResult;
import com.falconx.trading.engine.OpenPositionSnapshotStore;
import com.falconx.trading.engine.QuoteDrivenEngine;
import com.falconx.trading.repository.RedisTradingScheduleSnapshotRepository;
import com.falconx.trading.repository.TradingTradeRepository;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.entity.TradingTradeType;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import com.falconx.trading.service.model.TradingScheduleSnapshot;
import com.falconx.trading.service.model.TradingSessionWindow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * trading-core-service 真实持久化集成测试。
 *
 * <p>该测试覆盖 Stage 5 交易核心最关键的一条 owner 写入链路：
 *
 * <ol>
 *   <li>钱包确认入金进入 `t_account / t_deposit / t_ledger / t_outbox`</li>
 *   <li>最新报价进入 Redis 快照</li>
 *   <li>市价单进入 `t_order / t_position / t_trade / t_outbox`</li>
 *   <li>`t_outbox` 中的关键事件可被本地调度器真正投递到 Kafka</li>
 * </ol>
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
class TradingPersistenceIntegrationTests {

    @Autowired
    private TradingDepositCreditApplicationService tradingDepositCreditApplicationService;

    @Autowired
    private TradingOrderPlacementApplicationService tradingOrderPlacementApplicationService;

    @Autowired
    private TradingPositionCloseApplicationService tradingPositionCloseApplicationService;

    @Autowired
    private TradingTradeRepository tradingTradeRepository;

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
    void shouldPersistDepositOrderAndTradeFacts() {
        long userId = 92001L;
        OffsetDateTime now = OffsetDateTime.now();
        String txHash = "0xstage5trading001";

        try (KafkaConsumer<String, String> consumer = createConsumer("falconx.trading.deposit.credited")) {
            tradingDepositCreditApplicationService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                    "evt-stage5-trading-deposit-001",
                    88001L,
                    userId,
                    ChainType.ETH,
                    "USDT",
                    txHash,
                    new BigDecimal("2000.00000000"),
                    now
            ));
            waitForTopicRecord(consumer, txHash);
        }
        seedAlwaysOpenSchedule("BTCUSDT", "CRYPTO");
        quoteDrivenEngine.processTick(new MarketPriceTickEventPayload(
                "BTCUSDT",
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                new BigDecimal("9995.00000000"),
                now,
                "stage5-it",
                false
        ));
        tradingOrderPlacementApplicationService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId,
                "BTCUSDT",
                TradingOrderSide.BUY,
                new BigDecimal("1.00000000"),
                new BigDecimal("10"),
                null,
                new BigDecimal("10100.00000000"),
                new BigDecimal("9800.00000000"),
                "stage5-order-001"
        ));

        Integer accountCount = tradingTestSupportMapper.countAccountsByUserId(userId);
        Integer depositCount = tradingTestSupportMapper.countDepositsByUserId(userId);
        Integer depositWithWalletTxIdCount = tradingTestSupportMapper.countDepositsWithWalletTxIdByUserId(userId);
        Integer ledgerCount = tradingTestSupportMapper.countLedgerByUserId(userId);
        Integer orderCount = tradingTestSupportMapper.countOrdersByUserId(userId);
        Integer positionCount = tradingTestSupportMapper.countOpenPositionsByUserId(userId);
        Integer tradeCount = tradingTestSupportMapper.countTradesByUserId(userId);
        Integer outboxCount = tradingTestSupportMapper.countOutbox();
        Integer exposureCount = tradingTestSupportMapper.countRiskExposureBySymbol("BTCUSDT");
        String netExposure = tradingTestSupportMapper.selectRiskExposureNetBySymbol("BTCUSDT");
        String netExposureUsd = tradingTestSupportMapper.selectRiskExposureNetUsdBySymbol("BTCUSDT");
        Object markPrice = stringRedisTemplate.opsForHash()
                .get("falconx:trading:quote:snapshot:BTCUSDT", "mark");

        Assertions.assertEquals(1, accountCount);
        Assertions.assertEquals(1, depositCount);
        Assertions.assertEquals(1, depositWithWalletTxIdCount);
        Assertions.assertEquals(4, ledgerCount);
        Assertions.assertEquals(1, orderCount);
        Assertions.assertEquals(1, positionCount);
        Assertions.assertEquals(1, tradeCount);
        Assertions.assertEquals(3, outboxCount);
        Assertions.assertEquals(1, exposureCount);
        Assertions.assertEquals("1.00000000", netExposure);
        Assertions.assertEquals("9990.00000000", netExposureUsd);
        Assertions.assertEquals("9995.00000000", markPrice);
    }

    @Test
    void shouldRollbackManualCloseWhenRiskExposureUpdateFails() {
        long userId = 92002L;
        OffsetDateTime now = OffsetDateTime.now();

        tradingDepositCreditApplicationService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                "evt-stage7-trading-deposit-001",
                88002L,
                userId,
                ChainType.ETH,
                "USDT",
                "0xstage7trading001",
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

        Long positionId = tradingOrderPlacementApplicationService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId,
                "BTCUSDT",
                TradingOrderSide.BUY,
                new BigDecimal("1.00000000"),
                new BigDecimal("10"),
                null,
                new BigDecimal("10100.00000000"),
                new BigDecimal("9800.00000000"),
                "stage7-order-rollback-001"
        )).position().positionId();

        tradingTestSupportMapper.deleteRiskExposureBySymbol("BTCUSDT");
        publishQuote(
                "BTCUSDT",
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now()
        );

        IllegalStateException exception = Assertions.assertThrows(
                IllegalStateException.class,
                () -> tradingPositionCloseApplicationService.closePosition(
                        new CloseTradingPositionCommand(userId, positionId)
                )
        );

        Assertions.assertTrue(exception.getMessage().contains("Risk exposure"));
        Assertions.assertEquals("1995.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        Assertions.assertEquals("1000.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(userId));
        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(positionId));
        Assertions.assertNull(tradingTestSupportMapper.selectPositionClosePriceById(positionId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLedgerByUserIdAndBizType(userId, 8));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOrdersByUserId(userId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOpenPositionsByUserId(userId));
        Assertions.assertEquals(1, openPositionSnapshotStore.listOpenByUserId(userId).size());
    }

    @Test
    void shouldExposeOpenAndCloseTradesWithExplicitQueriesAfterManualClose() {
        long userId = 92003L;
        OffsetDateTime now = OffsetDateTime.now();
        tradingDepositCreditApplicationService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                "evt-stage7-trading-deposit-003",
                88003L,
                userId,
                ChainType.ETH,
                "USDT",
                "0xstage7trading003",
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

        PlaceMarketOrderCommand command = new PlaceMarketOrderCommand(
                userId,
                "BTCUSDT",
                TradingOrderSide.BUY,
                new BigDecimal("1.00000000"),
                new BigDecimal("10"),
                null,
                new BigDecimal("10100.00000000"),
                new BigDecimal("9800.00000000"),
                "stage7-order-trade-query-001"
        );
        OrderPlacementResult placementResult = tradingOrderPlacementApplicationService.placeMarketOrder(command);
        Long orderId = placementResult.order().orderId();
        Long positionId = placementResult.position().positionId();
        Long openTradeId = placementResult.trade().tradeId();

        publishQuote(
                "BTCUSDT",
                new BigDecimal("10045.00000000"),
                new BigDecimal("10055.00000000"),
                new BigDecimal("10050.00000000"),
                OffsetDateTime.now()
        );

        tradingPositionCloseApplicationService.closePosition(new CloseTradingPositionCommand(userId, positionId));

        TradingTrade openTrade = tradingTradeRepository.findOpenTradeByOrderId(orderId).orElseThrow();
        TradingTrade closeTrade = tradingTradeRepository.findByPositionIdAndTradeType(positionId, TradingTradeType.CLOSE).orElseThrow();
        OrderPlacementResult duplicateResult = tradingOrderPlacementApplicationService.placeMarketOrder(command);

        Assertions.assertEquals(TradingTradeType.OPEN, openTrade.tradeType());
        Assertions.assertEquals(orderId, openTrade.orderId());
        Assertions.assertEquals(positionId, openTrade.positionId());
        Assertions.assertEquals(openTradeId, openTrade.tradeId());
        Assertions.assertEquals(TradingTradeType.CLOSE, closeTrade.tradeType());
        Assertions.assertEquals(positionId, closeTrade.positionId());
        Assertions.assertNotEquals(openTrade.tradeId(), closeTrade.tradeId());
        Assertions.assertTrue(duplicateResult.duplicate());
        Assertions.assertNotNull(duplicateResult.trade());
        Assertions.assertEquals(TradingTradeType.OPEN, duplicateResult.trade().tradeType());
        Assertions.assertEquals(openTrade.tradeId(), duplicateResult.trade().tradeId());
        Assertions.assertEquals(1, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(positionId, 1));
        Assertions.assertEquals(1, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(positionId, 2));
        Assertions.assertEquals(1, tradingTestSupportMapper.countOrdersByUserId(userId));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectRiskExposureNetUsdBySymbol("BTCUSDT"));
    }

    @Test
    void shouldContinueProcessingOtherTriggeredPositionsWhenOneTriggeredCloseFails() {
        long failedUserId = 92004L;
        long succeededUserId = 92005L;
        Long failedPositionId = openPosition(failedUserId, TradingOrderSide.BUY, "stage7-trigger-isolation-004").position().positionId();
        Long succeededPositionId = openPosition(succeededUserId, TradingOrderSide.SELL, "stage7-trigger-isolation-005").position().positionId();

        tradingTestSupportMapper.updateRiskExposureQuantities(
                "BTCUSDT",
                BigDecimal.ZERO.setScale(8),
                new BigDecimal("1.00000000")
        );

        IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () -> publishQuote(
                "BTCUSDT",
                new BigDecimal("10100.00000000"),
                new BigDecimal("10105.00000000"),
                new BigDecimal("10102.50000000"),
                OffsetDateTime.now()
        ));

        Assertions.assertTrue(exception.getMessage().contains("Risk exposure"));

        Assertions.assertEquals(1, tradingTestSupportMapper.selectPositionStatusCodeById(failedPositionId));
        Assertions.assertNull(tradingTestSupportMapper.selectPositionClosePriceById(failedPositionId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(failedPositionId, 2));
        Assertions.assertEquals(0, tradingTestSupportMapper.countLedgerByUserIdAndBizType(failedUserId, 8));
        Assertions.assertEquals("1995.00000000", tradingTestSupportMapper.selectAccountBalanceByUserId(failedUserId));
        Assertions.assertEquals("1000.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(failedUserId));
        Assertions.assertEquals(1, openPositionSnapshotStore.listOpenByUserId(failedUserId).size());
        Assertions.assertEquals(failedPositionId, openPositionSnapshotStore.listOpenByUserId(failedUserId).getFirst().positionId());

        Assertions.assertEquals(2, tradingTestSupportMapper.selectPositionStatusCodeById(succeededPositionId));
        Assertions.assertEquals(3, tradingTestSupportMapper.selectPositionCloseReasonCodeById(succeededPositionId));
        Assertions.assertEquals("10105.00000000", tradingTestSupportMapper.selectPositionClosePriceById(succeededPositionId));
        Assertions.assertEquals("-115.00000000", tradingTestSupportMapper.selectPositionRealizedPnlById(succeededPositionId));
        Assertions.assertEquals(1, tradingTestSupportMapper.countTradesByPositionIdAndTradeType(succeededPositionId, 2));
        Assertions.assertEquals(1, tradingTestSupportMapper.countLedgerByUserIdAndBizType(succeededUserId, 8));
        Assertions.assertEquals("1880.00500000", tradingTestSupportMapper.selectAccountBalanceByUserId(succeededUserId));
        Assertions.assertEquals("0.00000000", tradingTestSupportMapper.selectAccountMarginUsedByUserId(succeededUserId));
        Assertions.assertTrue(openPositionSnapshotStore.listOpenByUserId(succeededUserId).isEmpty());

        Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.position.closed"));
    }

    private KafkaConsumer<String, String> createConsumer(String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "trading-persistence-it-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(List.of(topic));
        consumer.poll(Duration.ofMillis(200));
        return consumer;
    }

    private void waitForTopicRecord(KafkaConsumer<String, String> consumer, String txHash) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (record.value() != null && record.value().contains(txHash)) {
                    return;
                }
            }
        }
        Assertions.fail("Did not receive trading.deposit.credited message for txHash=" + txHash);
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
                "stage7-it",
                false
        ));
    }

    private OrderPlacementResult openPosition(long userId, TradingOrderSide side, String clientOrderId) {
        OffsetDateTime now = OffsetDateTime.now();
        tradingDepositCreditApplicationService.creditConfirmedDeposit(new CreditConfirmedDepositCommand(
                "evt-" + clientOrderId,
                99500L + userId,
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
}
