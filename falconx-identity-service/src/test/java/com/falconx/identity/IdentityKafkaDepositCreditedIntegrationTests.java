package com.falconx.identity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.falconx.identity.command.RegisterIdentityUserCommand;
import com.falconx.identity.config.IdentityServiceProperties;
import com.falconx.identity.contract.auth.RegisterResponse;
import com.falconx.identity.application.IdentityRegistrationApplicationService;
import com.falconx.identity.repository.mapper.test.IdentityTestSupportMapper;
import com.falconx.infrastructure.kafka.KafkaEventMessageSupport;
import com.falconx.trading.contract.event.DepositCreditedEventPayload;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * identity-service 真实 Kafka 入口集成测试。
 *
 * <p>该测试通过真实 topic `falconx.trading.deposit.credited` 驱动
 * `IdentityKafkaEventListener`，验证入金 credited 事件的幂等消费与 legacy 状态归一化语义。
 */
@ActiveProfiles("stage5")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(
        classes = IdentityServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_identity_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.port=6380",
                "falconx.identity.security.register-limit=1000",
                "falconx.identity.kafka.consumer-group-id=identity-kafka-deposit-credited-it-${random.uuid}"
        }
)
class IdentityKafkaDepositCreditedIntegrationTests {

    private static final String TOPIC = "falconx.trading.deposit.credited";
    private static final int USER_STATUS_LEGACY_PENDING_DEPOSIT = 0;
    private static final int USER_STATUS_ACTIVE = 1;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdentityServiceProperties identityServiceProperties;

    @Autowired
    private IdentityRegistrationApplicationService identityRegistrationApplicationService;

    @Autowired
    private IdentityTestSupportMapper identityTestSupportMapper;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void cleanOwnerTables() {
        identityTestSupportMapper.clearOwnerTables();
        stringRedisTemplate.delete("falconx:auth:register:limit:127.0.0.1");
    }

    @Test
    @Order(1)
    void shouldPersistInboxWhenActiveUserReceivesDepositCreditedViaKafka() throws Exception {
        RegisterResponse registerResponse = identityRegistrationApplicationService.register(
                new RegisterIdentityUserCommand("identity.kafka.inbox@example.com", "Passw0rd!", "127.0.0.1",
                        "Test", null, "User", java.time.LocalDate.of(2000, 1, 1), "CHN")
        );
        waitForKafkaListenerAssignment();

        DepositCreditedEventPayload payload = buildPayload(
                registerResponse.userId(),
                910001L,
                810001L,
                "0xidentitykafka001",
                OffsetDateTime.now().minusMinutes(5)
        );
        sendDepositCredited("evt-identity-kafka-activated-001", payload);

        waitForAssertion(() -> {
            Assertions.assertEquals(USER_STATUS_ACTIVE,
                    identityTestSupportMapper.selectUserStatusById(registerResponse.userId()));
            Assertions.assertEquals(1,
                    identityTestSupportMapper.countProcessedInboxByEventId("evt-identity-kafka-activated-001"));
            Assertions.assertNotNull(identityTestSupportMapper.selectActivatedAtByUserId(registerResponse.userId()));
            assertInboxPayloadMatches("evt-identity-kafka-activated-001", payload);
        }, "deposit.credited Kafka 事件未对 ACTIVE 用户完成幂等 inbox 落库");
    }

    @Test
    @Order(2)
    void shouldProcessDuplicateEventIdOnlyOnceWhenDepositCreditedArrivesTwiceViaKafka() throws Exception {
        RegisterResponse registerResponse = identityRegistrationApplicationService.register(
                new RegisterIdentityUserCommand("identity.kafka.duplicate@example.com", "Passw0rd!", "127.0.0.1",
                        "Test", null, "User", java.time.LocalDate.of(2000, 1, 1), "CHN")
        );
        waitForKafkaListenerAssignment();

        DepositCreditedEventPayload payload = buildPayload(
                registerResponse.userId(),
                910002L,
                810002L,
                "0xidentitykafka002",
                OffsetDateTime.now().minusMinutes(6)
        );
        sendDepositCredited("evt-identity-kafka-duplicate-001", payload);

        waitForAssertion(() -> {
            Assertions.assertEquals(USER_STATUS_ACTIVE,
                    identityTestSupportMapper.selectUserStatusById(registerResponse.userId()));
            Assertions.assertEquals(1,
                    identityTestSupportMapper.countProcessedInboxByEventId("evt-identity-kafka-duplicate-001"));
        }, "首次 deposit.credited 未对 ACTIVE 用户完成 inbox 落库");

        sendDepositCredited("evt-identity-kafka-duplicate-001", payload);

        waitForAssertion(() -> {
            Assertions.assertEquals(USER_STATUS_ACTIVE,
                    identityTestSupportMapper.selectUserStatusById(registerResponse.userId()));
            Assertions.assertEquals(1,
                    identityTestSupportMapper.countProcessedInboxByEventId("evt-identity-kafka-duplicate-001"));
        }, "相同 eventId 重复发送后不应重复激活或重复落库 Inbox");
    }

    @Test
    @Order(3)
    void shouldSkipActivationButPersistInboxWhenActiveUserReceivesNewDepositCreditedViaKafka() throws Exception {
        RegisterResponse registerResponse = identityRegistrationApplicationService.register(
                new RegisterIdentityUserCommand("identity.kafka.active@example.com", "Passw0rd!", "127.0.0.1",
                        "Test", null, "User", java.time.LocalDate.of(2000, 1, 1), "CHN")
        );
        waitForKafkaListenerAssignment();

        sendDepositCredited(
                "evt-identity-kafka-active-002",
                buildPayload(registerResponse.userId(), 910004L, 810004L, "0xidentitykafka004", OffsetDateTime.now().minusMinutes(6))
        );

        waitForAssertion(() -> {
            Assertions.assertEquals(USER_STATUS_ACTIVE,
                    identityTestSupportMapper.selectUserStatusById(registerResponse.userId()));
            Assertions.assertEquals(1,
                    identityTestSupportMapper.countProcessedInboxByEventId("evt-identity-kafka-active-002"));
        }, "ACTIVE 用户收到 deposit.credited 应保持状态并写成功 Inbox");
    }

    @Test
    @Order(4)
    void shouldRetryAndEventuallyActivateUserWhenMissingUserIsCreatedLaterViaKafka() throws Exception {
        long userId = 999999L;
        String eventId = "evt-identity-kafka-missing-user-001";
        DepositCreditedEventPayload payload = buildPayload(
                userId,
                910005L,
                810005L,
                "0xidentitykafka005",
                OffsetDateTime.now().minusMinutes(5)
        );
        waitForKafkaListenerAssignment();
        sendDepositCredited(eventId, payload);

        waitForDuration(Duration.ofSeconds(2));
        Assertions.assertEquals(0,
                identityTestSupportMapper.countUsersById(userId));
        Assertions.assertEquals(0,
                identityTestSupportMapper.countProcessedInboxByEventId(eventId));

        insertPendingDepositUser(userId);
        Assertions.assertEquals(USER_STATUS_LEGACY_PENDING_DEPOSIT,
                identityTestSupportMapper.selectUserStatusById(userId));

        waitForAssertion(() -> {
            Assertions.assertEquals(USER_STATUS_ACTIVE,
                    identityTestSupportMapper.selectUserStatusById(userId));
            Assertions.assertEquals(1,
                    identityTestSupportMapper.countProcessedInboxByEventId(eventId));
            Assertions.assertNotNull(identityTestSupportMapper.selectActivatedAtByUserId(userId));
            assertInboxPayloadMatches(eventId, payload);
        }, "缺失用户补录为 legacy PENDING_DEPOSIT 后，之前失败的 deposit.credited 未在重试中完成状态归一化");
    }

    /**
     * 发送一条真实的 `trading.deposit.credited` Kafka 事件。
     */
    private void sendDepositCredited(String eventId, DepositCreditedEventPayload payload) throws Exception {
        kafkaTemplate.send(KafkaEventMessageSupport.buildJsonMessage(
                        identityServiceProperties.getKafka().getDepositCreditedTopic(),
                        String.valueOf(payload.userId()),
                        objectMapper.writeValueAsString(payload),
                        eventId,
                        "trading.deposit.credited",
                        "falconx-trading-core-service"
                ))
                .get();
    }

    /**
     * 通过 owner 已落库的 `t_inbox.payload` 验证事件 payload 完整性，避免只验证“发送时构造了什么”。
     */
    private void assertInboxPayloadMatches(String eventId, DepositCreditedEventPayload payload) {
        try {
            String payloadJson = identityTestSupportMapper.selectProcessedInboxPayloadJsonByEventId(eventId);
            Assertions.assertNotNull(payloadJson);
            JsonNode root = objectMapper.readTree(payloadJson);
            Assertions.assertEquals(payload.depositId(), root.path("depositId").asLong());
            Assertions.assertEquals(payload.userId(), root.path("userId").asLong());
            Assertions.assertEquals(payload.accountId(), root.path("accountId").asLong());
            Assertions.assertEquals(payload.chain(), root.path("chain").asText());
            Assertions.assertEquals(payload.token(), root.path("token").asText());
            Assertions.assertEquals(payload.txHash(), root.path("txHash").asText());
            Assertions.assertTrue(root.hasNonNull("amount"));
            Assertions.assertEquals(0, root.path("amount").decimalValue().compareTo(payload.amount()));
            Instant actualCreditedAt = extractInstant(root.path("creditedAt"));
            long deltaNanos = Math.abs(Duration.between(payload.creditedAt().toInstant(), actualCreditedAt).toNanos());
            Assertions.assertTrue(deltaNanos <= 1_000L,
                    "creditedAt 与发送值不一致，deltaNanos=" + deltaNanos);
        } catch (Exception exception) {
            throw new AssertionError("identity inbox payload 与发送的 trading.deposit.credited 不一致", exception);
        }
    }

    private Instant extractInstant(JsonNode instantNode) {
        Assertions.assertTrue(instantNode != null && !instantNode.isNull() && !instantNode.isMissingNode());
        if (instantNode.isNumber()) {
            BigDecimal epochSeconds = instantNode.decimalValue();
            long seconds = epochSeconds.longValue();
            int nanos = epochSeconds.subtract(BigDecimal.valueOf(seconds))
                    .movePointRight(9)
                    .setScale(0, RoundingMode.HALF_UP)
                    .intValueExact();
            return Instant.ofEpochSecond(seconds, nanos);
        }
        return OffsetDateTime.parse(instantNode.asText()).toInstant();
    }

    /**
     * 等待 identity 的 Kafka listener 真正拿到分区，避免 `latest` 组在 join 前错过测试消息。
     */
    private void waitForKafkaListenerAssignment() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!kafkaListenerEndpointRegistry.getListenerContainers().isEmpty()) {
                boolean ready = kafkaListenerEndpointRegistry.getListenerContainers().stream()
                        .allMatch(this::hasAssignments);
                if (ready) {
                    return;
                }
            }
            waitForDuration(Duration.ofMillis(200));
        }
        Assertions.fail("identity Kafka listener 未在超时内完成分区分配");
    }

    private boolean hasAssignments(MessageListenerContainer container) {
        return container.isRunning()
                && container.getAssignedPartitions() != null
                && !container.getAssignedPartitions().isEmpty();
    }

    private DepositCreditedEventPayload buildPayload(Long userId,
                                                     Long depositId,
                                                     Long accountId,
                                                     String txHash,
                                                     OffsetDateTime creditedAt) {
        return new DepositCreditedEventPayload(
                depositId,
                userId,
                accountId,
                "ETH",
                "USDT",
                txHash,
                new BigDecimal("100.00000000"),
                creditedAt
        );
    }

    private void insertPendingDepositUser(Long userId) {
        identityTestSupportMapper.insertPendingDepositUser(
                userId,
                "U" + userId,
                "identity.kafka.missing." + userId + "@example.com",
                "$2a$10$identity.kafka.test.hash"
        );
    }

    private void waitForAssertion(Runnable assertion, String failureMessage) throws Exception {
        AssertionError lastError = null;
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                assertion.run();
                return;
            } catch (AssertionError error) {
                lastError = error;
                waitForDuration(Duration.ofMillis(200));
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
}
