package com.falconx.trading;

import com.falconx.identity.contract.event.KycReviewedEventPayload;
import com.falconx.infrastructure.kafka.KafkaEventMessageSupport;
import com.falconx.trading.repository.mapper.test.TradingTestSupportMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * STAGE-6-KYC trading-core 真实 Kafka KYC 消费集成测试。
 *
 * <p>覆盖 TC-KYC-060~064：通过 docker-compose Kafka 推送 {@code falconx.identity.kyc.reviewed}，
 * 验证 trading-core {@link com.falconx.trading.consumer.KycReviewedEventConsumer}
 * 完成写 {@code t_notification} + 幂等 + 缺字段错误 + 消费组名称固定。
 *
 * <p>注意：现有 {@link com.falconx.trading.consumer.KycReviewedEventConsumerTests}
 * 是 mock 单测，保留作为快速回归；本类是真 Kafka + 真 DB 的端到端验证。
 */
@ActiveProfiles("stage5")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = TradingCoreServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:mysql://localhost:3306/falconx_trading_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "spring.datasource.username=root",
                "spring.datasource.password=root",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6380",
                "falconx.trading.kafka.consumer-group-id=trading-kyc-it-${random.uuid}",
                "falconx.trading.kafka.kyc-reviewed-consumer-group-id=trading-kyc-reviewed-it-${random.uuid}"
        }
)
class TradingKycReviewedKafkaIntegrationTests {

    private static final String TOPIC = "falconx.identity.kyc.reviewed";
    private static final String RELATED_KEY = "kyc.reviewed";
    private static final int LEVEL_INFO = 1;
    private static final int LEVEL_WARN = 2;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private TradingTestSupportMapper supportMapper;

    @BeforeEach
    void cleanOwnerTables() throws InterruptedException {
        supportMapper.clearOwnerTables();
        waitForKafkaListenerAssignment();
    }

    /** TC-KYC-060 APPROVED → 站内信 INFO + title="KYC 已通过"。 */
    @Test
    void shouldCreateInfoNotificationOnApproved() throws Exception {
        long submissionId = 60000001L;
        long userId = 60000101L;
        KycReviewedEventPayload payload = new KycReviewedEventPayload(
                submissionId, userId, "APPROVED", 1, 777L,
                OffsetDateTime.now(ZoneOffset.UTC), null
        );
        sendKycReviewed("evt-kyc-it-approved-001", payload);

        waitForAssertion(() -> {
            Assertions.assertEquals(1, supportMapper.countNotificationByRelated(RELATED_KEY, submissionId));
            Assertions.assertEquals(LEVEL_INFO,
                    supportMapper.selectNotificationLevelCodeByRelated(RELATED_KEY, submissionId));
            Assertions.assertEquals("KYC 已通过",
                    supportMapper.selectNotificationTitleByRelated(RELATED_KEY, submissionId));
            // STAGE-8-NOTIFICATION 后 t_notification.type 列改存 templateCode（V22 schema）
            Assertions.assertEquals("KYC_APPROVED",
                    supportMapper.selectNotificationTypeByRelated(RELATED_KEY, submissionId));
        });
    }

    /** TC-KYC-061 REJECTED → 站内信 WARN + body 含 rejectReason。 */
    @Test
    void shouldCreateWarnNotificationOnRejectedWithReason() throws Exception {
        long submissionId = 60000002L;
        long userId = 60000102L;
        KycReviewedEventPayload payload = new KycReviewedEventPayload(
                submissionId, userId, "REJECTED", 0, 778L,
                OffsetDateTime.now(ZoneOffset.UTC), "证件不清晰"
        );
        sendKycReviewed("evt-kyc-it-rejected-001", payload);

        waitForAssertion(() -> {
            Assertions.assertEquals(1, supportMapper.countNotificationByRelated(RELATED_KEY, submissionId));
            Assertions.assertEquals(LEVEL_WARN,
                    supportMapper.selectNotificationLevelCodeByRelated(RELATED_KEY, submissionId));
            Assertions.assertEquals("KYC 未通过",
                    supportMapper.selectNotificationTitleByRelated(RELATED_KEY, submissionId));
            String body = supportMapper.selectNotificationBodyByRelated(RELATED_KEY, submissionId);
            Assertions.assertNotNull(body);
            Assertions.assertTrue(body.contains("证件不清晰"),
                    "body 必须包含 rejectReason，实际：" + body);
        });
    }

    /** TC-KYC-062 重复消费（同 submissionId 两次）→ t_notification 仅 1 行（按 relatedKey+relatedId 查重）。 */
    @Test
    void shouldRemainSingleNotificationWhenSameSubmissionConsumedTwice() throws Exception {
        long submissionId = 60000003L;
        long userId = 60000103L;
        KycReviewedEventPayload payload = new KycReviewedEventPayload(
                submissionId, userId, "APPROVED", 1, 779L,
                OffsetDateTime.now(ZoneOffset.UTC), null
        );
        sendKycReviewed("evt-kyc-it-dup-001", payload);
        waitForAssertion(() -> Assertions.assertEquals(1,
                supportMapper.countNotificationByRelated(RELATED_KEY, submissionId)));

        // 不同 eventId 但相同 submissionId（at-least-once 重投）
        sendKycReviewed("evt-kyc-it-dup-002", payload);
        // 等待 2s 让消费完成（即便重复也要走过 consume() 判幂等）
        TimeUnit.SECONDS.sleep(2);
        Assertions.assertEquals(1, supportMapper.countNotificationByRelated(RELATED_KEY, submissionId),
                "幂等：同 submissionId 二次消费不应再写一条站内信");
    }

    /** TC-KYC-063 payload 缺 result 字段 → 消费者抛出 IllegalArgumentException（listener 进 DLQ 前置）。 */
    @Test
    void shouldRejectPayloadWithMissingResultField() throws Exception {
        long submissionId = 60000004L;
        long userId = 60000104L;
        // 手动构造缺字段 JSON 直接发，不走 record 序列化以保证字段确实缺失
        String malformedJson = String.format(
                "{\"submissionId\":%d,\"userId\":%d,\"kycLevel\":0}",
                submissionId, userId
        );
        kafkaTemplate.send(KafkaEventMessageSupport.buildJsonMessage(
                        TOPIC,
                        String.valueOf(userId),
                        malformedJson,
                        "evt-kyc-it-malformed-001",
                        "identity.kyc.reviewed",
                        "falconx-identity-service-it"))
                .get();

        // 等待 listener 处理（抛异常 → 不写库）
        TimeUnit.SECONDS.sleep(3);
        Assertions.assertEquals(0, supportMapper.countNotificationByRelated(RELATED_KEY, submissionId),
                "缺字段 payload 不得写入 t_notification");
    }

    /**
     * TC-KYC-064 消费组名固定。
     *
     * <p>验证 trading-core 上注册了名为 {@code falconx.trading-core-service.kyc-reviewed-consumer-group}
     * 的 KYC listener；本测试使用 random uuid 后缀的 group 仅为测试隔离（property override）。
     * 这里通过断言 {@link com.falconx.trading.config.TradingCoreServiceProperties.Kafka}
     * 默认值固定，保证 dev/prod 行为一致。
     */
    @Test
    void shouldFixConsumerGroupIdInProperties() {
        com.falconx.trading.config.TradingCoreServiceProperties defaults =
                new com.falconx.trading.config.TradingCoreServiceProperties();
        Assertions.assertEquals(
                "falconx.trading-core-service.kyc-reviewed-consumer-group",
                defaults.getKafka().getKycReviewedConsumerGroupId(),
                "KYC consumer group default 必须固定为 falconx.trading-core-service.kyc-reviewed-consumer-group");
    }

    private void sendKycReviewed(String eventId, KycReviewedEventPayload payload) throws Exception {
        String json = objectMapper.writeValueAsString(payload);
        kafkaTemplate.send(KafkaEventMessageSupport.buildJsonMessage(
                        TOPIC,
                        String.valueOf(payload.userId()),
                        json,
                        eventId,
                        "identity.kyc.reviewed",
                        "falconx-identity-service-it"))
                .get();
    }

    private void waitForKafkaListenerAssignment() throws InterruptedException {
        // 2026-05-20 加固：
        //   1) 15s 在 Docker 冷启 + JVM 预热下经常超时（Kafka rebalance 需要 ~10-20s）→ 拉到 60s
        //   2) allMatch 要求所有 container ready 容易卡 — Spring 自动注册的不相关 topic listener
        //      partition 分配慢会拖住判断。改 anyMatch：任一 listener rebalance 完成即视为 Kafka
        //      已 boot，后续具体消息收到与否由 waitForAssertion 兜底。
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!kafkaListenerEndpointRegistry.getListenerContainers().isEmpty()) {
                boolean anyReady = kafkaListenerEndpointRegistry.getListenerContainers().stream()
                        .anyMatch(this::hasAssignments);
                if (anyReady) {
                    return;
                }
            }
            Thread.sleep(200);
        }
        Assertions.fail("trading-core Kafka listener 未在 60s 内完成分区分配");
    }

    private boolean hasAssignments(MessageListenerContainer container) {
        return container.isRunning()
                && container.getAssignedPartitions() != null
                && !container.getAssignedPartitions().isEmpty();
    }

    private void waitForAssertion(Runnable assertion) throws InterruptedException {
        AssertionError last = null;
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                last = e;
                Thread.sleep(Duration.ofMillis(200).toMillis());
            }
        }
        if (last != null) throw last;
        Assertions.fail("等待断言超时");
    }
}
