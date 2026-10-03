package com.falconx.identity;

import com.falconx.identity.application.IdentityKycApplicationService;
import com.falconx.identity.application.IdentityKycApplicationService.DocumentPayload;
import com.falconx.identity.application.IdentityRegistrationApplicationService;
import com.falconx.identity.command.RegisterIdentityUserCommand;
import com.falconx.identity.contract.auth.RegisterResponse;
import com.falconx.identity.entity.KycDocumentType;
import com.falconx.identity.entity.KycIdType;
import com.falconx.identity.entity.KycSubmission;
import com.falconx.identity.repository.mapper.test.IdentityTestSupportMapper;
import com.falconx.infrastructure.kafka.KafkaEventHeaderConstants;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * STAGE-6-KYC identity Kafka 事件发布集成测试。
 *
 * <p>覆盖 TC-KYC-050~053：approve/reject 事务 afterCommit 发布 {@code falconx.identity.kyc.reviewed}，
 * 真实 Kafka（docker-compose）订阅断言 headers + key + payload + 事务回滚不发事件。
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
                "falconx.identity.kafka.consumer-group-id=identity-kyc-publisher-it-${random.uuid}"
        }
)
class IdentityKycEventPublisherIntegrationTests {

    private static final String TOPIC = "falconx.identity.kyc.reviewed";
    private static final String FRONT_B64 = encode("publisher-front");
    private static final String BACK_B64 = encode("publisher-back");
    private static final String SELFIE_B64 = encode("publisher-selfie");

    @Autowired
    private IdentityRegistrationApplicationService registrationService;

    @Autowired
    private IdentityKycApplicationService kycService;

    @Autowired
    private IdentityTestSupportMapper supportMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        supportMapper.clearOwnerTables();
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "kyc-publisher-it-" + UUID.randomUUID());
        // latest：新 group 无 committed offset → 从分配时间点的末尾开始，避免吃历史消息
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(TOPIC));
        // 等待 partition assignment 完成（最多 10s）以保证后续 publish 的消息能被这个 consumer 收到
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

    /** TC-KYC-050 approve 后发布 APPROVED 事件，含 X-Event-Type / X-Event-Source / key=userId / kycLevel=1。 */
    @Test
    void shouldPublishApprovedEventWithHeadersAndPayload() throws Exception {
        long userId = registerUser("kyc-pub-approved@example.com");
        KycSubmission s = submitFor(userId);

        kycService.approve(s.id(), 777L);

        ConsumerRecord<String, String> record = pollOne();
        Assertions.assertNotNull(record, "approve 后应能从 Kafka 拉到 1 条事件");
        Assertions.assertEquals(String.valueOf(userId), record.key(), "Kafka key 必须等于 userId");
        Assertions.assertEquals("identity.kyc.reviewed", headerValue(record, KafkaEventHeaderConstants.EVENT_TYPE_HEADER));
        Assertions.assertEquals("falconx-identity-service", headerValue(record, KafkaEventHeaderConstants.EVENT_SOURCE_HEADER));
        Assertions.assertNotNull(headerValue(record, KafkaEventHeaderConstants.EVENT_ID_HEADER));

        JsonNode body = objectMapper.readTree(record.value());
        Assertions.assertEquals(s.id().longValue(), body.path("submissionId").asLong());
        Assertions.assertEquals(userId, body.path("userId").asLong());
        Assertions.assertEquals("APPROVED", body.path("result").asText());
        Assertions.assertEquals(1, body.path("kycLevel").asInt());
        Assertions.assertEquals(777L, body.path("reviewerId").asLong());
        Assertions.assertTrue(body.hasNonNull("reviewAt"));
        Assertions.assertTrue(body.path("rejectReason").isNull() || body.path("rejectReason").asText().isEmpty(),
                "APPROVED 时 rejectReason 应为 null");
    }

    /** TC-KYC-051 reject 后发布 REJECTED 事件，含非空 rejectReason + kycLevel=0。 */
    @Test
    void shouldPublishRejectedEventWithReason() throws Exception {
        long userId = registerUser("kyc-pub-rejected@example.com");
        KycSubmission s = submitFor(userId);

        kycService.reject(s.id(), 888L, "证件不清晰");

        ConsumerRecord<String, String> record = pollOne();
        Assertions.assertNotNull(record, "reject 后应能从 Kafka 拉到 1 条事件");
        Assertions.assertEquals(String.valueOf(userId), record.key());

        JsonNode body = objectMapper.readTree(record.value());
        Assertions.assertEquals("REJECTED", body.path("result").asText());
        Assertions.assertEquals(0, body.path("kycLevel").asInt());
        Assertions.assertEquals("证件不清晰", body.path("rejectReason").asText());
        Assertions.assertEquals(888L, body.path("reviewerId").asLong());
    }

    /** TC-KYC-052 事务回滚不发事件：手动构造 afterCommit 失败场景，验证回滚后无消息。 */
    @Test
    void shouldNotPublishWhenTransactionRollsBack() throws Exception {
        long userId = registerUser("kyc-pub-rollback@example.com");
        KycSubmission s = submitFor(userId);

        // 通过事务回滚验证 afterCommit hook 不会发：在事务内 approve，然后抛异常回滚
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        Assertions.assertThrows(RuntimeException.class, () -> tx.execute(status -> {
            kycService.approve(s.id(), 555L);
            status.setRollbackOnly();
            throw new RuntimeException("force rollback");
        }));

        ConsumerRecord<String, String> record = pollOne();
        Assertions.assertNull(record, "事务回滚后不应有 Kafka 消息发出");
        // submission 仍然 PENDING（updateReview 与 user kyc_level 都已回滚）
        Assertions.assertEquals(Integer.valueOf(0), supportMapper.selectKycSubmissionStatusById(s.id()));
        Assertions.assertEquals(Integer.valueOf(0), supportMapper.selectKycLevelByUserId(userId));
    }

    /** TC-KYC-053 partition key 是 userId 的字符串形式。 */
    @Test
    void shouldUseUserIdAsPartitionKey() throws Exception {
        long userId = registerUser("kyc-pub-key@example.com");
        KycSubmission s = submitFor(userId);

        kycService.approve(s.id(), 777L);

        ConsumerRecord<String, String> record = pollOne();
        Assertions.assertNotNull(record);
        Assertions.assertEquals(String.valueOf(userId), record.key(),
                "partition key 必须为 userId 字符串形式（确保同用户消息有序）");
    }

    private long registerUser(String email) {
        RegisterResponse r = registrationService.register(
                new RegisterIdentityUserCommand(email, "Passw0rd!", "127.0.0.1",
                        "Test", null, "User", LocalDate.of(2000, 1, 1), "CHN")
        );
        return r.userId();
    }

    private KycSubmission submitFor(long userId) {
        Map<KycDocumentType, DocumentPayload> docs = new HashMap<>();
        docs.put(KycDocumentType.ID_FRONT, new DocumentPayload(FRONT_B64, "image/jpeg"));
        docs.put(KycDocumentType.ID_BACK, new DocumentPayload(BACK_B64, "image/jpeg"));
        docs.put(KycDocumentType.HOLDING_SELFIE, new DocumentPayload(SELFIE_B64, "image/jpeg"));
        return kycService.submit(userId, KycIdType.ID_CARD, "110101199001011234", docs);
    }

    /**
     * 从 Kafka 拉一条 KYC reviewed 消息，最多等待 10s；超时返回 null。
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

    private static String headerValue(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static String encode(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
