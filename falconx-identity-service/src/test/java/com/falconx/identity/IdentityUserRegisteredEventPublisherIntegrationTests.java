package com.falconx.identity;

import com.falconx.identity.application.IdentityRegistrationApplicationService;
import com.falconx.identity.command.RegisterIdentityUserCommand;
import com.falconx.identity.contract.auth.RegisterResponse;
import com.falconx.identity.repository.mapper.test.IdentityTestSupportMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * STAGE-5-WALLET-PROVISION identity Kafka 事件发布集成测试。
 *
 * <p>覆盖 TC-WP-001 ~ TC-WP-004：注册事务 afterCommit 发布
 * {@code falconx.identity.user.registered}，真实 Kafka（docker-compose）订阅断言
 * key + payload 6 字段 + 事务回滚不发事件。
 *
 * <p>注：当前 publisher 不使用 {@code KafkaEventMessageSupport}（P2 待办，详见
 * 管理端接口规范 §11.6），因此本 IT 仅断言 Kafka key + payload body 内的字段，
 * 不断言 Kafka headers。
 */
@ActiveProfiles("stage5")
@SpringBootTest(
        classes = IdentityServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_identity_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.port=6380",
                "falconx.identity.security.register-limit=1000",
                "falconx.identity.security.login-failure-limit=100",
                "falconx.identity.kafka.consumer-group-id=identity-user-registered-publisher-it-${random.uuid}"
        }
)
class IdentityUserRegisteredEventPublisherIntegrationTests {

    private static final String TOPIC = "falconx.identity.user.registered";

    @Autowired
    private IdentityRegistrationApplicationService registrationService;

    @Autowired
    private IdentityTestSupportMapper supportMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        supportMapper.clearOwnerTables();
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "user-registered-publisher-it-" + UUID.randomUUID());
        // latest：新 group 无 committed offset → 从分配时间点的末尾开始，避免吃历史消息
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(TOPIC));
        long deadline = System.currentTimeMillis() + 10_000L;
        while (consumer.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(200));
        }
        Assertions.assertFalse(consumer.assignment().isEmpty(),
                "consumer 未在 10s 内完成 partition 分配，Kafka 可能不可用");
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    /** TC-WP-001 注册成功 afterCommit 发布事件，payload 6 字段全到位。 */
    @Test
    void shouldPublishUserRegisteredEventWithPayloadFields() throws Exception {
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(2);
        long userId = registerUser("wp-001@example.com");

        ConsumerRecord<String, String> record = pollOne();
        Assertions.assertNotNull(record, "注册后应能从 Kafka 拉到 1 条 user.registered 事件");

        Assertions.assertEquals(String.valueOf(userId), record.key(), "Kafka key 必须等于 userId 字符串形式");

        JsonNode body = objectMapper.readTree(record.value());
        Assertions.assertEquals("user-registered-" + userId, body.path("eventId").asText());
        Assertions.assertEquals("identity.user.registered", body.path("eventType").asText());
        Assertions.assertEquals(userId, body.path("userId").asLong());
        Assertions.assertTrue(body.hasNonNull("uid"), "uid 字段必须存在且非 null");
        Assertions.assertFalse(body.path("uid").asText().isBlank(), "uid 非空白");
        Assertions.assertEquals("wp-001@example.com", body.path("email").asText());

        OffsetDateTime registeredAt = OffsetDateTime.parse(body.path("registeredAt").asText());
        Assertions.assertTrue(registeredAt.isAfter(before),
                "registeredAt 必须 ≥ 测试启动时间");
        Assertions.assertTrue(registeredAt.isBefore(OffsetDateTime.now().plusSeconds(2)),
                "registeredAt 必须 ≤ 当前时间 + 2s 容差");
    }

    /** TC-WP-002 事务回滚不发事件：在事务内 register 然后 setRollbackOnly + throw。 */
    @Test
    void shouldNotPublishWhenTransactionRollsBack() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Assertions.assertThrows(RuntimeException.class, () -> tx.execute(status -> {
            registrationService.register(
                    new RegisterIdentityUserCommand(
                            "wp-002@example.com", "Passw0rd!", "127.0.0.1",
                            "Roll", null, "Back", LocalDate.of(2000, 1, 1), "CHN")
            );
            status.setRollbackOnly();
            throw new RuntimeException("force rollback");
        }));

        ConsumerRecord<String, String> record = pollOne();
        Assertions.assertNull(record, "事务回滚后不应有 Kafka 消息发出");
    }

    /** TC-WP-003 partition key 严格等于 userId 字符串形式（确保同用户消息有序）。 */
    @Test
    void shouldUseUserIdAsPartitionKey() throws Exception {
        long userId = registerUser("wp-003@example.com");

        ConsumerRecord<String, String> record = pollOne();
        Assertions.assertNotNull(record);
        Assertions.assertEquals(String.valueOf(userId), record.key(),
                "partition key 必须为 userId 字符串形式（确保同用户消息有序）");
    }

    /** TC-WP-004 eventId 固定格式 user-registered-{userId}。 */
    @Test
    void shouldUseDeterministicEventIdFormat() throws Exception {
        long userId = registerUser("wp-004@example.com");

        ConsumerRecord<String, String> record = pollOne();
        Assertions.assertNotNull(record);
        JsonNode body = objectMapper.readTree(record.value());
        Assertions.assertEquals("user-registered-" + userId, body.path("eventId").asText(),
                "eventId 必须固定为 user-registered-{userId}（DLQ 入库去重键）");
    }

    private long registerUser(String email) {
        RegisterResponse r = registrationService.register(
                new RegisterIdentityUserCommand(email, "Passw0rd!", "127.0.0.1",
                        "Test", null, "User", LocalDate.of(2000, 1, 1), "CHN")
        );
        return r.userId();
    }

    /**
     * 从 Kafka 拉一条 user.registered 消息，最多等待 10s；超时返回 null。
     */
    private ConsumerRecord<String, String> pollOne() {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> r : records) {
                if (TOPIC.equals(r.topic())) {
                    return r;
                }
            }
        }
        return null;
    }
}
