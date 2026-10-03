package com.falconx.identity.producer;

import com.falconx.identity.config.IdentityServiceProperties;
import com.falconx.identity.contract.event.KycReviewedEventPayload;
import com.falconx.infrastructure.kafka.KafkaEventMessageSupport;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * STAGE-5-WALLET-PROVISION：identity 内部事件发布器。
 *
 * <p>当前仅承接 {@code falconx.identity.user.registered}，由
 * {@link com.falconx.identity.application.IdentityRegistrationApplicationService}
 * 在注册事务 afterCommit 调用，触发 wallet-service 异步分配 TRC20 / ERC20 入金地址。
 * 失败仅记日志，不回滚注册结果——避免 Kafka 抖动让用户注册失败。
 */
@Component
public class IdentityKafkaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(IdentityKafkaEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final IdentityServiceProperties properties;
    private final ObjectMapper objectMapper;

    public IdentityKafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                        IdentityServiceProperties properties,
                                        ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void publishUserRegistered(long userId, String uid, String email) {
        String topic = properties.getKafka().getUserRegisteredTopic();
        String eventId = "user-registered-" + userId;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventType", "identity.user.registered");
        payload.put("userId", userId);
        payload.put("uid", uid);
        payload.put("email", email);
        payload.put("registeredAt", OffsetDateTime.now());
        try {
            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(topic, String.valueOf(userId), json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("identity.kafka.user-registered.publish-failed userId={} reason={}",
                                    userId, ex.toString(), ex);
                        } else {
                            log.info("identity.kafka.user-registered.published userId={} topic={} partition={} offset={}",
                                    userId, topic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (RuntimeException ex) {
            log.error("identity.kafka.user-registered.serialize-failed userId={} reason={}",
                    userId, ex.toString(), ex);
        }
    }

    /**
     * STAGE-6-KYC Phase 4：发布 KYC 审核完成事件。
     *
     * <p>由 {@link com.falconx.identity.application.IdentityKycApplicationService}
     * 在 approve / reject 事务 afterCommit 调用，触发 trading-core-service 写
     * {@code t_notification} 并经 WebSocket {@code notification.created} 推送给目标用户。
     * 失败仅记日志，不影响审核事实——KYC 审核结果已落库，站内信只是用户体验补偿。
     *
     * @param payload KYC 审核事件 payload；契约见 {@link KycReviewedEventPayload}
     */
    public void publishKycReviewed(KycReviewedEventPayload payload) {
        String topic = properties.getKafka().getKycReviewedTopic();
        String eventId = "kyc-reviewed-" + payload.submissionId();
        try {
            String json = objectMapper.writeValueAsString(payload);
            Message<String> message = KafkaEventMessageSupport.buildJsonMessage(
                    topic,
                    String.valueOf(payload.userId()),
                    json,
                    eventId,
                    "identity.kyc.reviewed",
                    "falconx-identity-service"
            );
            kafkaTemplate.send(message)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("identity.kafka.kyc-reviewed.publish-failed submissionId={} userId={} reason={}",
                                    payload.submissionId(), payload.userId(), ex.toString(), ex);
                        } else {
                            log.info("identity.kafka.kyc-reviewed.published submissionId={} userId={} result={} topic={} partition={} offset={}",
                                    payload.submissionId(), payload.userId(), payload.result(), topic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (RuntimeException ex) {
            log.error("identity.kafka.kyc-reviewed.serialize-failed submissionId={} userId={} reason={}",
                    payload.submissionId(), payload.userId(), ex.toString(), ex);
        }
    }
}
