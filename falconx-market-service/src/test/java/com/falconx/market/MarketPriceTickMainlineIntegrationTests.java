package com.falconx.market;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.falconx.infrastructure.kafka.KafkaEventHeaderConstants;
import com.falconx.market.analytics.mapper.test.MarketAnalyticsTestSupportMapper;
import com.falconx.market.application.MarketDataIngestionApplicationService;
import com.falconx.market.entity.StandardQuote;
import com.falconx.market.provider.ExternalRawQuote;
import com.falconx.market.repository.MarketLatestQuoteRepository;
import com.falconx.market.support.MarketMybatisTestSupportConfiguration;
import com.falconx.market.support.MarketTestDatabaseInitializer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * `market.price.tick` 主链路集成测试。
 *
 * <p>该测试专门补齐 Stage 6A 下 market-service 的正式证据：
 * 同一条 LP 原始报价进入服务后，必须把一致的 `bid / ask / mid / mark`
 * 同时落到 Redis 最新价、ClickHouse `quote_tick` 和 Kafka `falconx.market.price.tick`。
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
                "falconx.market.analytics.quote-batch-size=1",
                "falconx.market.analytics.quote-flush-interval=50",
                "falconx.market.analytics.jdbc-url=jdbc:clickhouse://localhost:8123/falconx_market_analytics",
                "falconx.market.analytics.username=default",
                "falconx.market.analytics.password=falconx"
        }
)
class MarketPriceTickMainlineIntegrationTests {

    private static final String PRICE_TICK_TOPIC = "falconx.market.price.tick";

    @Autowired
    private MarketDataIngestionApplicationService marketDataIngestionApplicationService;

    @Autowired
    private MarketLatestQuoteRepository marketLatestQuoteRepository;

    @Autowired
    private MarketAnalyticsTestSupportMapper marketAnalyticsTestSupportMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearStores() {
        stringRedisTemplate.delete("falconx:market:price:EURUSD");
        marketAnalyticsTestSupportMapper.clearAnalyticsTables();
    }

    @Test
    void shouldKeepPriceTickConsistentAcrossRedisClickHouseAndKafka() throws Exception {
        try (KafkaConsumer<String, String> consumer = createTopicConsumer(PRICE_TICK_TOPIC)) {
            OffsetDateTime quoteTime = OffsetDateTime.now(ZoneOffset.UTC).withNano(123_000_000);
            ExternalRawQuote rawQuote = new ExternalRawQuote(
                    "EURUSD",
                    new BigDecimal("1.08100000"),
                    new BigDecimal("1.08120000"),
                    quoteTime,
                    "TM_QUOTE"
            );

            StandardQuote expectedQuote = marketDataIngestionApplicationService.ingest(rawQuote);
            StandardQuote redisQuote = waitForRedisQuote("EURUSD");
            Map<String, Object> clickHouseQuote = waitForClickHouseQuote("EURUSD");
            ConsumerRecord<String, String> kafkaRecord = waitForTopicRecord(consumer, "EURUSD", quoteTime);
            JsonNode kafkaPayload = objectMapper.readTree(kafkaRecord.value());

            assertQuoteEquals(expectedQuote, redisQuote);
            Assertions.assertEquals("EURUSD", kafkaRecord.key());
            Assertions.assertEquals("market.price.tick", headerValue(kafkaRecord, KafkaEventHeaderConstants.EVENT_TYPE_HEADER));
            Assertions.assertEquals("falconx-market-service",
                    headerValue(kafkaRecord, KafkaEventHeaderConstants.EVENT_SOURCE_HEADER));
            Assertions.assertNotNull(headerValue(kafkaRecord, KafkaEventHeaderConstants.EVENT_ID_HEADER));
            Assertions.assertNotNull(headerValue(kafkaRecord, KafkaEventHeaderConstants.TRACE_ID_HEADER));
            Assertions.assertFalse(kafkaPayload.has("payload"));
            Assertions.assertFalse(kafkaPayload.has("eventId"));

            Assertions.assertEquals("EURUSD", kafkaPayload.path("symbol").asText());
            Assertions.assertEquals(0, kafkaPayload.path("bid").decimalValue().compareTo(expectedQuote.bid()));
            Assertions.assertEquals(0, kafkaPayload.path("ask").decimalValue().compareTo(expectedQuote.ask()));
            Assertions.assertEquals(0, kafkaPayload.path("mid").decimalValue().compareTo(expectedQuote.mid()));
            Assertions.assertEquals(0, kafkaPayload.path("mark").decimalValue().compareTo(expectedQuote.mark()));
            Assertions.assertEquals("TM_QUOTE", kafkaPayload.path("source").asText());
            Assertions.assertFalse(kafkaPayload.path("stale").asBoolean());
            Assertions.assertEquals("FRESH", kafkaPayload.path("quoteStatus").asText());
            Assertions.assertTrue(kafkaPayload.path("ts").isNumber());
            Assertions.assertEquals(quoteTime.toInstant(), parseKafkaTimestamp(kafkaPayload.path("ts").decimalValue()));

            Assertions.assertEquals("EURUSD", clickHouseQuote.get("symbol"));
            Assertions.assertEquals("TM_QUOTE", clickHouseQuote.get("source"));
            Assertions.assertEquals(0, toBigDecimal(clickHouseQuote.get("bidPrice")).compareTo(expectedQuote.bid()));
            Assertions.assertEquals(0, toBigDecimal(clickHouseQuote.get("askPrice")).compareTo(expectedQuote.ask()));
            Assertions.assertEquals(0, toBigDecimal(clickHouseQuote.get("midPrice")).compareTo(expectedQuote.mid()));
            Assertions.assertEquals(0, toBigDecimal(clickHouseQuote.get("markPrice")).compareTo(expectedQuote.mark()));
        }
    }

    private KafkaConsumer<String, String> createTopicConsumer(String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "market-price-tick-it-consumer-" + UUID.randomUUID());
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

    private StandardQuote waitForRedisQuote(String symbol) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 12_000L;
        while (System.currentTimeMillis() < deadline) {
            StandardQuote quote = marketLatestQuoteRepository.findBySymbol(symbol).orElse(null);
            if (quote != null) {
                return quote;
            }
            Thread.sleep(200L);
        }
        Assertions.fail("未在超时内从 Redis 读到最新报价 symbol=" + symbol);
        return null;
    }

    private Map<String, Object> waitForClickHouseQuote(String symbol) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 12_000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> latestQuote = marketAnalyticsTestSupportMapper.selectLatestQuoteTickBySymbol(symbol);
            if (latestQuote != null && !latestQuote.isEmpty()) {
                return latestQuote;
            }
            Thread.sleep(200L);
        }
        Assertions.fail("未在超时内从 ClickHouse 读到 quote_tick symbol=" + symbol);
        return Map.of();
    }

    private ConsumerRecord<String, String> waitForTopicRecord(KafkaConsumer<String, String> consumer,
                                                              String symbol,
                                                              OffsetDateTime quoteTime) throws Exception {
        long deadline = System.currentTimeMillis() + 12_000L;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (symbol.equals(record.key()) && isExpectedPayload(record.value(), quoteTime)) {
                    return record;
                }
            }
        }
        Assertions.fail("未在超时内收到 symbol=" + symbol + " 的 market.price.tick Kafka 消息");
        return null;
    }

    private boolean isExpectedPayload(String value, OffsetDateTime quoteTime) throws Exception {
        JsonNode payload = objectMapper.readTree(value);
        return "FRESH".equals(payload.path("quoteStatus").asText())
                && quoteTime.toInstant().equals(parseKafkaTimestamp(payload.path("ts").decimalValue()));
    }

    private void assertQuoteEquals(StandardQuote expected, StandardQuote actual) {
        Assertions.assertEquals(expected.symbol(), actual.symbol());
        Assertions.assertEquals(0, expected.bid().compareTo(actual.bid()));
        Assertions.assertEquals(0, expected.ask().compareTo(actual.ask()));
        Assertions.assertEquals(0, expected.mid().compareTo(actual.mid()));
        Assertions.assertEquals(0, expected.mark().compareTo(actual.mark()));
        Assertions.assertEquals(expected.source(), actual.source());
        Assertions.assertEquals(expected.ts().toInstant().toEpochMilli(), actual.ts().toInstant().toEpochMilli());
        Assertions.assertEquals(expected.stale(), actual.stale());
    }

    private String headerValue(ConsumerRecord<String, String> record, String headerName) {
        if (record.headers().lastHeader(headerName) == null) {
            return null;
        }
        return new String(record.headers().lastHeader(headerName).value(), StandardCharsets.UTF_8);
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private Instant parseKafkaTimestamp(BigDecimal timestamp) {
        BigDecimal normalized = timestamp.stripTrailingZeros();
        BigDecimal secondsPart = normalized.setScale(0, RoundingMode.DOWN);
        BigDecimal nanosPart = normalized.subtract(secondsPart)
                .movePointRight(9)
                .setScale(0, RoundingMode.HALF_UP);
        return Instant.ofEpochSecond(secondsPart.longValueExact(), nanosPart.longValueExact());
    }
}
