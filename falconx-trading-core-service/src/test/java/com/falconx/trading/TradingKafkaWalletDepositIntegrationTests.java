package com.falconx.trading;

import tools.jackson.databind.ObjectMapper;
import com.falconx.domain.enums.ChainType;
import com.falconx.infrastructure.kafka.KafkaEventMessageSupport;
import com.falconx.trading.producer.TradingOutboxDispatcher;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import com.falconx.wallet.contract.event.WalletDepositConfirmedEventPayload;
import com.falconx.wallet.contract.event.WalletDepositReversedEventPayload;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * wallet -> trading 真实 Kafka 入口集成测试。
 *
 * <p>该测试必须通过真实 Kafka topic 驱动 `TradingKafkaEventListener`，
 * 验证钱包入金 confirmed / reversed 事件进入 trading-core 后的最小正式语义：
 *
 * <ul>
 *   <li>`wallet.deposit.confirmed` 会真正落到 `t_inbox / t_deposit / t_outbox / t_account`</li>
 *   <li>重复 confirmed 按 `walletTxId` 维持业务幂等，不重复入账</li>
 *   <li>`wallet.deposit.reversed` 会真正回滚已入账业务事实</li>
 *   <li>`reversed` 先到时初始阶段不允许提前写成功 Inbox；待 confirmed 到达后，失败的 reversal
 *       会在重试中完成最终回滚</li>
 * </ul>
 */
@ActiveProfiles("stage5")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(
        classes = TradingCoreServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380",
                "falconx.trading.kafka.consumer-group-id=trading-kafka-wallet-it-${random.uuid}"
        }
)
class TradingKafkaWalletDepositIntegrationTests {

    private static final String WALLET_DEPOSIT_CONFIRMED_TOPIC = "falconx.wallet.deposit.confirmed";
    private static final String WALLET_DEPOSIT_REVERSED_TOPIC = "falconx.wallet.deposit.reversed";
    private static final String TRADING_DEPOSIT_CREDITED_TOPIC = "falconx.trading.deposit.credited";
    private static final int DEPOSIT_STATUS_CREDITED = 1;
    private static final int DEPOSIT_STATUS_REVERSED = 2;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private TradingTestSupportMapper tradingTestSupportMapper;

    @Autowired
    private TradingOutboxDispatcher tradingOutboxDispatcher;

    @BeforeEach
    void cleanOwnerTables() {
        tradingTestSupportMapper.clearOwnerTables();
    }

    @Test
    @Order(1)
    void shouldCreditDepositWhenConfirmedMessageArrivesViaKafka() throws Exception {
        long walletTxId = 910001L;
        long userId = 830001L;
        BigDecimal amount = new BigDecimal("125.25000000");
        String txHash = "0xkafkaconfirmed0001";
        String eventId = "evt-wallet-confirmed-0001";
        OffsetDateTime confirmedAt = OffsetDateTime.now().minusMinutes(10);

        try (KafkaConsumer<String, String> creditedConsumer = createTopicConsumer(TRADING_DEPOSIT_CREDITED_TOPIC)) {
            sendConfirmedEvent(eventId, new WalletDepositConfirmedEventPayload(
                    walletTxId,
                    userId,
                    ChainType.ETH,
                    "USDT",
                    txHash,
                    "0xfrom0001",
                    "0xto0001",
                    amount,
                    12,
                    12,
                    confirmedAt
            ));

            waitForDatabaseAssertion(() -> {
                Assertions.assertEquals(1, tradingTestSupportMapper.countDepositsByWalletTxId(walletTxId));
                Assertions.assertEquals(DEPOSIT_STATUS_CREDITED,
                        tradingTestSupportMapper.selectDepositStatusCodeByWalletTxId(walletTxId));
                Assertions.assertEquals(1, tradingTestSupportMapper.countInboxByEventId(eventId));
                Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.deposit.credited"));
                Assertions.assertEquals("125.25000000",
                        tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
            }, "confirmed Kafka 消息未驱动 trading-core 完成业务入账");

            dispatchOutboxNow();
            waitForTopicRecord(creditedConsumer, txHash);
        }
    }

    @Test
    @Order(2)
    void shouldNotCreditTwiceWhenDuplicateConfirmedMessageArrivesViaKafka() throws Exception {
        long walletTxId = 910002L;
        long userId = 830002L;
        BigDecimal amount = new BigDecimal("88.00000000");
        String txHash = "0xkafkaconfirmed0002";
        String firstEventId = "evt-wallet-confirmed-0002-a";
        String duplicateEventId = "evt-wallet-confirmed-0002-b";
        OffsetDateTime confirmedAt = OffsetDateTime.now().minusMinutes(11);

        try (KafkaConsumer<String, String> creditedConsumer = createTopicConsumer(TRADING_DEPOSIT_CREDITED_TOPIC)) {
            WalletDepositConfirmedEventPayload payload = new WalletDepositConfirmedEventPayload(
                    walletTxId,
                    userId,
                    ChainType.ETH,
                    "USDT",
                    txHash,
                    "0xfrom0002",
                    "0xto0002",
                    amount,
                    15,
                    12,
                    confirmedAt
            );
            sendConfirmedEvent(firstEventId, payload);
            waitForDatabaseAssertion(() -> Assertions.assertEquals(1,
                    tradingTestSupportMapper.countDepositsByWalletTxId(walletTxId)), "首次 confirmed 未成功入账");
            dispatchOutboxNow();
            waitForTopicRecord(creditedConsumer, txHash);

            sendConfirmedEvent(duplicateEventId, payload);

            waitForDatabaseAssertion(() -> {
                Assertions.assertEquals(1, tradingTestSupportMapper.countDepositsByWalletTxId(walletTxId));
                Assertions.assertEquals(DEPOSIT_STATUS_CREDITED,
                        tradingTestSupportMapper.selectDepositStatusCodeByWalletTxId(walletTxId));
                Assertions.assertEquals(1, tradingTestSupportMapper.countInboxByEventId(firstEventId));
                Assertions.assertEquals(1, tradingTestSupportMapper.countInboxByEventId(duplicateEventId));
                Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.deposit.credited"));
                Assertions.assertEquals("88.00000000",
                        tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
            }, "duplicate confirmed 不应重复入账");

            dispatchOutboxNow();
            assertNoAdditionalTopicRecord(creditedConsumer, txHash, Duration.ofSeconds(3));
        }
    }

    @Test
    @Order(3)
    void shouldReverseCreditedDepositWhenReversedMessageArrivesViaKafka() throws Exception {
        long walletTxId = 910003L;
        long userId = 830003L;
        BigDecimal amount = new BigDecimal("50.50000000");
        String txHash = "0xkafkareversed0003";
        String confirmedEventId = "evt-wallet-confirmed-0003";
        String reversedEventId = "evt-wallet-reversed-0003";
        OffsetDateTime confirmedAt = OffsetDateTime.now().minusMinutes(12);
        OffsetDateTime reversedAt = confirmedAt.plusMinutes(2);

        sendConfirmedEvent(confirmedEventId, new WalletDepositConfirmedEventPayload(
                walletTxId,
                userId,
                ChainType.BSC,
                "USDT",
                txHash,
                "0xfrom0003",
                "0xto0003",
                amount,
                12,
                12,
                confirmedAt
        ));

        waitForDatabaseAssertion(() -> Assertions.assertEquals(1,
                tradingTestSupportMapper.countDepositsByWalletTxId(walletTxId)), "reversed 用例前置 confirmed 未成功入账");

        sendReversedEvent(reversedEventId, new WalletDepositReversedEventPayload(
                walletTxId,
                userId,
                ChainType.BSC,
                "USDT",
                txHash,
                "0xfrom0003",
                "0xto0003",
                amount,
                1,
                12,
                reversedAt
        ));

        waitForDatabaseAssertion(() -> {
            Assertions.assertEquals(1, tradingTestSupportMapper.countDepositsByWalletTxId(walletTxId));
            Assertions.assertEquals(DEPOSIT_STATUS_REVERSED,
                    tradingTestSupportMapper.selectDepositStatusCodeByWalletTxId(walletTxId));
            Assertions.assertEquals(1, tradingTestSupportMapper.countInboxByEventId(confirmedEventId));
            Assertions.assertEquals(1, tradingTestSupportMapper.countInboxByEventId(reversedEventId));
            Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.deposit.credited"));
            Assertions.assertEquals("0.00000000",
                    tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        }, "reversed Kafka 消息未驱动已入账业务事实回滚");
    }

    @Test
    @Order(4)
    void shouldRetryAndEventuallyReverseDepositWhenReversedArrivesBeforeConfirmedViaKafka() throws Exception {
        long walletTxId = 910004L;
        long userId = 830004L;
        BigDecimal amount = new BigDecimal("77.77000000");
        String txHash = "0xkafkareversed0004";
        String reversedEventId = "evt-wallet-reversed-0004";
        String confirmedEventId = "evt-wallet-confirmed-0004";
        OffsetDateTime reversedAt = OffsetDateTime.now().minusMinutes(13);
        OffsetDateTime confirmedAt = reversedAt.plusMinutes(1);

        sendReversedEvent(reversedEventId, new WalletDepositReversedEventPayload(
                walletTxId,
                userId,
                ChainType.ETH,
                "USDT",
                txHash,
                "0xfrom0004",
                "0xto0004",
                amount,
                1,
                12,
                reversedAt
        ));

        waitForDuration(Duration.ofSeconds(2));
        Assertions.assertEquals(0, tradingTestSupportMapper.countDepositsByWalletTxId(walletTxId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countInboxByEventId(reversedEventId));
        Assertions.assertEquals(0, tradingTestSupportMapper.countOutboxByEventType("trading.deposit.credited"));
        Assertions.assertNull(tradingTestSupportMapper.selectAccountBalanceByUserId(userId));

        sendConfirmedEvent(confirmedEventId, new WalletDepositConfirmedEventPayload(
                walletTxId,
                userId,
                ChainType.ETH,
                "USDT",
                txHash,
                "0xfrom0004",
                "0xto0004",
                amount,
                12,
                12,
                confirmedAt
        ));

        waitForDatabaseAssertion(() -> {
            Assertions.assertEquals(1, tradingTestSupportMapper.countDepositsByWalletTxId(walletTxId));
            Assertions.assertEquals(DEPOSIT_STATUS_REVERSED,
                    tradingTestSupportMapper.selectDepositStatusCodeByWalletTxId(walletTxId));
            Assertions.assertEquals(1, tradingTestSupportMapper.countInboxByEventId(reversedEventId));
            Assertions.assertEquals(1, tradingTestSupportMapper.countInboxByEventId(confirmedEventId));
            Assertions.assertEquals(1, tradingTestSupportMapper.countOutboxByEventType("trading.deposit.credited"));
            Assertions.assertEquals("0.00000000",
                    tradingTestSupportMapper.selectAccountBalanceByUserId(userId));
        }, "reversed 先到后应在 confirmed 到达后通过重试完成最终回滚");
    }

    /**
     * 通过真实 Kafka topic 发送 confirmed 事件。
     */
    private void sendConfirmedEvent(String eventId, WalletDepositConfirmedEventPayload payload) throws Exception {
        kafkaTemplate.send(KafkaEventMessageSupport.buildJsonMessage(
                        WALLET_DEPOSIT_CONFIRMED_TOPIC,
                        String.valueOf(payload.userId()),
                        objectMapper.writeValueAsString(payload),
                        eventId,
                        "wallet.deposit.confirmed",
                        "falconx-wallet-service"
                ))
                .get(5, TimeUnit.SECONDS);
    }

    /**
     * 通过真实 Kafka topic 发送 reversed 事件。
     */
    private void sendReversedEvent(String eventId, WalletDepositReversedEventPayload payload) throws Exception {
        kafkaTemplate.send(KafkaEventMessageSupport.buildJsonMessage(
                        WALLET_DEPOSIT_REVERSED_TOPIC,
                        String.valueOf(payload.userId()),
                        objectMapper.writeValueAsString(payload),
                        eventId,
                        "wallet.deposit.reversed",
                        "falconx-wallet-service"
                ))
                .get(5, TimeUnit.SECONDS);
    }

    /**
     * 创建一个只消费测试关注 topic 的原生 Kafka consumer，并把读取起点固定到“当前最新 offset”。
     *
     * <p>这里不再使用 `subscribe + group join + latest`，避免 consumer 在 rebalance / seekToEnd 时序里
     * 错过测试刚刚产出的 `trading.deposit.credited` 消息。
     */
    private KafkaConsumer<String, String> createTopicConsumer(String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "trading-kafka-wallet-it-consumer-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        List<TopicPartition> partitions = awaitTopicPartitions(consumer, topic);
        consumer.assign(partitions);
        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
        endOffsets.forEach(consumer::seek);
        return consumer;
    }

    /**
     * 等待目标 topic 元数据可用，并返回其全部分区。
     */
    private List<TopicPartition> awaitTopicPartitions(KafkaConsumer<String, String> consumer, String topic) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic, Duration.ofMillis(500));
            if (partitionInfos != null && !partitionInfos.isEmpty()) {
                return partitionInfos.stream()
                        .map(partitionInfo -> new TopicPartition(partitionInfo.topic(), partitionInfo.partition()))
                        .toList();
            }
        }
        Assertions.fail("测试消费者未能在超时内加载 topic=" + topic + " 的分区信息");
        return List.of();
    }

    /**
     * 显式触发一次 Outbox 分发，避免测试依赖定时调度器触发时序。
     */
    private void dispatchOutboxNow() {
        tradingOutboxDispatcher.dispatchPendingMessages();
    }

    /**
     * 轮询等待数据库断言成立，避免把 Kafka / scheduler 的异步时序误判成业务失败。
     */
    private void waitForDatabaseAssertion(Runnable assertion, String failureMessage) throws Exception {
        AssertionError lastError = null;
        long deadline = System.currentTimeMillis() + 12_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                assertion.run();
                return;
            } catch (AssertionError error) {
                lastError = error;
                waitForDuration(Duration.ofMillis(250));
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        Assertions.fail(failureMessage);
    }

    /**
     * 等待指定 txHash 的 credited 事件真正从 trading outbox 发到 Kafka。
     */
    private void waitForTopicRecord(KafkaConsumer<String, String> consumer, String txHash) {
        long deadline = System.currentTimeMillis() + 12_000L;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (record.value() != null && record.value().contains(txHash)) {
                    return;
                }
            }
        }
        Assertions.fail("未收到 txHash=" + txHash + " 对应的 trading.deposit.credited Kafka 消息");
    }

    /**
     * duplicate confirmed 不应生成第二条 credited 跨服务消息。
     */
    private void assertNoAdditionalTopicRecord(KafkaConsumer<String, String> consumer, String txHash, Duration duration) {
        long deadline = System.currentTimeMillis() + duration.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                if (record.value() != null && record.value().contains(txHash)) {
                    Assertions.fail("duplicate confirmed 不应再次发送 trading.deposit.credited，txHash=" + txHash);
                }
            }
        }
    }

    private void waitForDuration(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }
}
