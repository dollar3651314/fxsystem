package com.falconx.market.integration;

import tools.jackson.databind.ObjectMapper;
import com.falconx.infrastructure.kafka.KafkaEventHeaderConstants;
import com.falconx.market.MarketServiceApplication;
import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.market.producer.FxRateKafkaPublisher;
import com.falconx.market.service.FxRateService;
import com.falconx.market.support.MarketMybatisTestSupportConfiguration;
import com.falconx.market.support.MarketTestDatabaseInitializer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * STAGE-14A Task 10 — FX Kafka 发布集成测试（TC-004 / TC-005）。
 *
 * <p>测试策略：
 * <ul>
 *   <li>TC-FX-IT-004：与 {@code MarketPriceTickMainlineIntegrationTests} 对齐，使用真实 Kafka（localhost:9092）。
 *       手动触发 {@link FxRateKafkaPublisher#publish()}，验证在指定时间内 topic {@code falconx.market.fx.rate.update}
 *       可收到至少 1 条 EUR:USD 消息。</li>
 *   <li>TC-FX-IT-005：{@link FxRateSnapshotPayload} 反序列化 contract 一致性测试——
 *       序列化再反序列化后 6 字段与原始对象相等。</li>
 * </ul>
 */
@ActiveProfiles("stage5")
@ContextConfiguration(initializers = MarketTestDatabaseInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = {
                MarketServiceApplication.class,
                MarketMybatisTestSupportConfiguration.class
        },
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_market_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380",
                "spring.kafka.bootstrap-servers=localhost:9092",
                "falconx.market.analytics.jdbc-url=jdbc:clickhouse://localhost:8123/falconx_market_analytics",
                "falconx.market.analytics.username=default",
                "falconx.market.analytics.password=falconx",
                "falconx.market.fx.redis-ttl-seconds=5",
                /* 节流 1Hz；关闭 LP 外部连接，测试通过手动 trigger publish() 验证 */
                "falconx.market.fx.kafka-throttle-interval-ms=1000",
                "falconx.market.lp.enabled=false"
        }
)
class FxRateKafkaIntegrationTests {

    private static final String FX_RATE_UPDATE_TOPIC = "falconx.market.fx.rate.update";

    @Autowired
    private FxRateService fxRateService;

    @Autowired
    private FxRateKafkaPublisher fxRateKafkaPublisher;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * TC-FX-IT-004: FxRateKafkaPublisher.publish() 发布后，topic 中可收到 EUR:USD 消息。
     *
     * <p>向内存快照写入一条 EUR/USD tick，手动调用 {@link FxRateKafkaPublisher#publish()}，
     * 在真实 Kafka topic {@code falconx.market.fx.rate.update} 上等待并验证消息正确到达。
     */
    @Test
    void tc004_publishSendsEurUsdMessageToKafkaTopic() throws Exception {
        long now = System.currentTimeMillis();
        FxRateSnapshotPayload eurUsd = new FxRateSnapshotPayload(
                "EUR", "USD",
                new BigDecimal("1.08750"),
                now,
                "GODSA", "EURUSD"
        );
        fxRateService.acceptTick(eurUsd);

        try (KafkaConsumer<String, String> consumer = createTopicConsumer(FX_RATE_UPDATE_TOPIC)) {
            /* 手动触发一次发布（不依赖 @Scheduled 定时触发，确保测试确定性） */
            fxRateKafkaPublisher.publish();

            ConsumerRecord<String, String> record = waitForRecord(consumer, "EUR:USD", 10_000L);

            Assertions.assertNotNull(record, "应在超时内收到 key=EUR:USD 的 Kafka 消息");
            Assertions.assertEquals("EUR:USD", record.key());

            /* 验证 Kafka 事件头存在 */
            String eventType = headerValue(record, KafkaEventHeaderConstants.EVENT_TYPE_HEADER);
            Assertions.assertEquals("market.fx.rate.update", eventType,
                    "事件类型头应为 market.fx.rate.update");

            String eventSource = headerValue(record, KafkaEventHeaderConstants.EVENT_SOURCE_HEADER);
            Assertions.assertEquals("falconx-market-service", eventSource,
                    "事件来源头应为 falconx-market-service");

            Assertions.assertNotNull(headerValue(record, KafkaEventHeaderConstants.EVENT_ID_HEADER),
                    "eventId 头不应为空");

            /* 验证消息 payload 含正确汇率 */
            tools.jackson.databind.JsonNode payload = objectMapper.readTree(record.value());
            Assertions.assertEquals("EUR", payload.path("baseCurrency").asText());
            Assertions.assertEquals("USD", payload.path("quoteCurrency").asText());
            Assertions.assertEquals(0,
                    payload.path("rate").decimalValue().compareTo(new BigDecimal("1.08750")),
                    "Kafka payload rate 应与 tick 一致");
        }
    }

    /**
     * TC-FX-IT-005: {@link FxRateSnapshotPayload} 6 字段反序列化后与原始契约一致。
     *
     * <p>通过 Jackson 序列化再反序列化验证：
     * baseCurrency / quoteCurrency / rate / eventTimeMillis / sourceLpCode / sourceSymbol
     * 6 个字段全部可以无损往返。
     */
    @Test
    void tc005_fxRateSnapshotPayloadDeserializationMatchesContract() throws Exception {
        long ts = System.currentTimeMillis();
        FxRateSnapshotPayload original = new FxRateSnapshotPayload(
                "GBP", "USD",
                new BigDecimal("1.26500"),
                ts,
                "GODSA", "GBPUSD"
        );

        String json = objectMapper.writeValueAsString(original);
        FxRateSnapshotPayload deserialized = objectMapper.readValue(json, FxRateSnapshotPayload.class);

        Assertions.assertEquals(original.baseCurrency(), deserialized.baseCurrency(),
                "baseCurrency 反序列化应一致");
        Assertions.assertEquals(original.quoteCurrency(), deserialized.quoteCurrency(),
                "quoteCurrency 反序列化应一致");
        Assertions.assertEquals(0, original.rate().compareTo(deserialized.rate()),
                "rate 反序列化应一致");
        Assertions.assertEquals(original.eventTimeMillis(), deserialized.eventTimeMillis(),
                "eventTimeMillis 反序列化应一致");
        Assertions.assertEquals(original.sourceLpCode(), deserialized.sourceLpCode(),
                "sourceLpCode 反序列化应一致");
        Assertions.assertEquals(original.sourceSymbol(), deserialized.sourceSymbol(),
                "sourceSymbol 反序列化应一致");

        /* 验证 @JsonIgnoreProperties(ignoreUnknown = true) 生效：额外字段不影响反序列化 */
        String jsonWithExtra = json.substring(0, json.length() - 1) + ",\"unknownField\":\"ignored\"}";
        FxRateSnapshotPayload withExtra = objectMapper.readValue(jsonWithExtra, FxRateSnapshotPayload.class);
        Assertions.assertEquals(original.baseCurrency(), withExtra.baseCurrency(),
                "ignoreUnknown 生效时 baseCurrency 应保持不变");
    }

    /* ===== 辅助方法 ===== */

    private KafkaConsumer<String, String> createTopicConsumer(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "fx-rate-kafka-it-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        List<TopicPartition> partitions = awaitTopicPartitions(consumer, topic);
        consumer.assign(partitions);
        /* 从末尾开始消费，只捕获本次测试发布的消息 */
        consumer.endOffsets(partitions).forEach(consumer::seek);
        return consumer;
    }

    private List<TopicPartition> awaitTopicPartitions(KafkaConsumer<String, String> consumer, String topic) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic, Duration.ofMillis(500));
            if (infos != null && !infos.isEmpty()) {
                return infos.stream()
                        .map(p -> new TopicPartition(p.topic(), p.partition()))
                        .toList();
            }
        }
        Assertions.fail("未在超时内加载 topic=" + topic + " 的分区信息");
        return List.of();
    }

    private ConsumerRecord<String, String> waitForRecord(KafkaConsumer<String, String> consumer,
                                                          String key,
                                                          long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                if (key.equals(r.key())) {
                    return r;
                }
            }
        }
        return null;
    }

    private String headerValue(ConsumerRecord<String, String> record, String headerName) {
        if (record.headers().lastHeader(headerName) == null) {
            return null;
        }
        return new String(record.headers().lastHeader(headerName).value(), StandardCharsets.UTF_8);
    }
}
