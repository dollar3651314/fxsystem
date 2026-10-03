package com.falconx.trading;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import com.falconx.infrastructure.kafka.KafkaEventMessageSupport;
import com.falconx.market.contract.event.MarketKlineUpdateEventPayload;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.dto.PriceTickProcessingResult;
import com.falconx.trading.engine.QuoteDrivenEngine;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import java.util.List;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * market -> trading 真实 Kafka 入口集成测试。
 *
 * <p>本组用例覆盖 Stage 6A 当前新增的两个收口点：
 *
 * <ul>
 *   <li>`market.kline.update` 在 trading-core 的正式消费</li>
 *   <li>`market.price.tick` 在 Kafka 入口失败后的显式重试</li>
 * </ul>
 */
@ActiveProfiles("stage5")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = {
                TradingCoreServiceApplication.class,
                TradingKafkaMarketEventIntegrationTests.QuoteDrivenEngineRetryTestConfiguration.class
        },
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380",
                "falconx.trading.kafka.consumer-group-id=trading-kafka-market-it-${random.uuid}"
        }
)
class TradingKafkaMarketEventIntegrationTests {

    // 2026-05-27 修 flaky：用随机 IT 专属 topic 隔离本地/CI 同一 Kafka 上 market-service 持续
    // 推送的真实 price.tick / kline.update（之前 consumer 订阅固定 topic 消费实时流 → attempts=147 / inbox=11）。
    // 关键：topic 名 + 预创建必须在 @DynamicPropertySource（context 启动 / listener 订阅之前）完成，
    // 否则 consumer subscribe 时 topic 不存在，后创建无法可靠 rebalance assign。
    private static final String RUN_ID = java.util.UUID.randomUUID().toString();
    private static final String MARKET_PRICE_TICK_TOPIC = "falconx.market.price.tick.it-" + RUN_ID;
    private static final String MARKET_KLINE_UPDATE_TOPIC = "falconx.market.kline.update.it-" + RUN_ID;

    @org.springframework.test.context.DynamicPropertySource
    static void registerItTopics(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("falconx.trading.kafka.market-price-tick-topic", () -> MARKET_PRICE_TICK_TOPIC);
        registry.add("falconx.trading.kafka.market-kline-update-topic", () -> MARKET_KLINE_UPDATE_TOPIC);
        // 在 context 启动前预创建 topic，确保 listener 订阅时 topic 已存在 → consumer 直接 assign
        preCreateTopic(MARKET_PRICE_TICK_TOPIC);
        preCreateTopic(MARKET_KLINE_UPDATE_TOPIC);
    }

    private static void preCreateTopic(String topic) {
        try (Admin admin = Admin.create(java.util.Map.of("bootstrap.servers", "localhost:9092"))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // topic 已存在等：幂等忽略
        }
    }

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private TradingTestSupportMapper tradingTestSupportMapper;

    @Autowired
    private QuoteDrivenEngine quoteDrivenEngine;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @BeforeEach
    void cleanOwnerTables() {
        tradingTestSupportMapper.clearOwnerTables();
        reset(quoteDrivenEngine);
    }

    @Test
    void shouldConsumeMarketKlineUpdateViaKafkaAndRecordInboxFact() throws Exception {
        ensureTopicReadyAndAssigned(MARKET_KLINE_UPDATE_TOPIC);

        String eventId = "evt-market-kline-0001";
        MarketKlineUpdateEventPayload payload = new MarketKlineUpdateEventPayload(
                "EURUSD",
                "1m",
                new BigDecimal("1.08000000"),
                new BigDecimal("1.08200000"),
                new BigDecimal("1.07950000"),
                new BigDecimal("1.08150000"),
                new BigDecimal("12.34000000"),
                OffsetDateTime.parse("2026-04-20T12:00:00Z"),
                OffsetDateTime.parse("2026-04-20T12:00:59Z"),
                true
        );

        kafkaTemplate.send(KafkaEventMessageSupport.buildJsonMessage(
                        MARKET_KLINE_UPDATE_TOPIC,
                        payload.symbol() + ":" + payload.interval(),
                        objectMapper.writeValueAsString(payload),
                        eventId,
                        "market.kline.update",
                        "falconx-market-service"
                ))
                .get(5, TimeUnit.SECONDS);

        waitForAssertion(() -> {
            Assertions.assertEquals(1, tradingTestSupportMapper.countInboxByEventId(eventId));
            Assertions.assertEquals(1, tradingTestSupportMapper.countInboxByEventType("market.kline.update"));
        }, "market.kline.update 未在 trading-core 写入 Inbox 审计事实");
    }

    @Test
    void shouldRetryMarketPriceTickAtKafkaEntryAndEventuallySucceed() throws Exception {
        ensureTopicReadyAndAssigned(MARKET_PRICE_TICK_TOPIC);

        String eventId = "evt-market-tick-0001";
        AtomicInteger attempts = new AtomicInteger();
        when(quoteDrivenEngine.processTick(any(MarketPriceTickEventPayload.class))).thenAnswer(invocation -> {
            int currentAttempt = attempts.incrementAndGet();
            if (currentAttempt == 1) {
                throw new IllegalStateException("simulated price tick failure");
            }
            return new PriceTickProcessingResult(
                    new TradingQuoteSnapshot(
                            "BTCUSDT",
                            new BigDecimal("9990.00000000"),
                            new BigDecimal("10000.00000000"),
                            new BigDecimal("9995.00000000"),
                            OffsetDateTime.parse("2026-04-20T12:01:00Z"),
                            "market-kafka-it",
                            false
                    ),
                    0
            );
        });

        MarketPriceTickEventPayload payload = new MarketPriceTickEventPayload(
                "BTCUSDT",
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                new BigDecimal("9995.00000000"),
                OffsetDateTime.parse("2026-04-20T12:01:00Z"),
                "market-kafka-it",
                false
        );

        kafkaTemplate.send(KafkaEventMessageSupport.buildJsonMessage(
                        MARKET_PRICE_TICK_TOPIC,
                        payload.symbol(),
                        objectMapper.writeValueAsString(payload),
                        eventId,
                        "market.price.tick",
                        "falconx-market-service"
                ))
                .get(5, TimeUnit.SECONDS);

        waitForAssertion(() -> Assertions.assertTrue(attempts.get() >= 2),
                "market.price.tick Kafka 入口失败后未触发重试");
        waitForDuration(Duration.ofSeconds(1));

        Assertions.assertEquals(2, attempts.get());
        Assertions.assertEquals(0, tradingTestSupportMapper.countInboxByEventId(eventId));
    }

    /**
     * 2026-05-27 修 flaky：随机 IT topic 是 auto-create，consumer（subscribe 模式）动态
     * 发现新 topic 不可靠（依赖 metadata 刷新 + rebalance 时序）。改为：
     * 1. 用 AdminClient 显式预创建 topic（幂等）
     * 2. 等到有 container 真正 assign 了**该 topic**的 partition（而非 anyMatch 任意 topic）
     * 这样发消息时 consumer 已订阅到该 topic 的 partition，earliest 能读到 offset 0 的测试消息。
     */
    private void ensureTopicReadyAndAssigned(String topic) throws Exception {
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // topic 已存在（TopicExistsException）等：幂等忽略
        }
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            boolean assigned = kafkaListenerEndpointRegistry.getListenerContainers().stream()
                    .anyMatch(c -> c.isRunning()
                            && c.getAssignedPartitions() != null
                            && c.getAssignedPartitions().stream().anyMatch(tp -> topic.equals(tp.topic())));
            if (assigned) {
                return;
            }
            Thread.sleep(250);
        }
        Assertions.fail("consumer 未在 60s 内 assign topic " + topic);
    }

    private void waitForAssertion(Runnable assertion, String failureMessage) throws Exception {
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

    private void waitForDuration(Duration duration) throws InterruptedException {
        Thread.sleep(duration.toMillis());
    }

    @TestConfiguration
    static class QuoteDrivenEngineRetryTestConfiguration {

        @Bean
        @Primary
        QuoteDrivenEngine primaryQuoteDrivenEngineMock() {
            return mock(QuoteDrivenEngine.class);
        }
    }
}
