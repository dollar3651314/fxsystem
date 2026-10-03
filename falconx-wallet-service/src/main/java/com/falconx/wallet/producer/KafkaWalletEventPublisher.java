package com.falconx.wallet.producer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.infrastructure.kafka.KafkaEventMessageSupport;
import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.contract.event.WalletDepositConfirmedEventPayload;
import com.falconx.wallet.contract.event.WalletDepositDetectedEventPayload;
import com.falconx.wallet.contract.event.WalletDepositReversedEventPayload;
import com.falconx.wallet.entity.WalletOutboxMessage;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 钱包事件的 Kafka 发布实现。
 *
 * <p>该实现只负责“已落入发件箱的事件 -> Kafka”这一步骤。
 * 应用层若要发布低频关键事件，必须先通过 Outbox 落库，再由调度器调用本类真正发送。
 */
public class KafkaWalletEventPublisher implements WalletOutboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaWalletEventPublisher.class);

    private final WalletServiceProperties properties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;

    public KafkaWalletEventPublisher(WalletServiceProperties properties,
                                     KafkaTemplate<String, String> kafkaTemplate,
                                     ObjectMapper objectMapper,
                                     IdGenerator idGenerator) {
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public void publish(WalletOutboxMessage message) {
        send(resolveTopic(message.eventType()), message.partitionKey(), message.payload(), message.eventType(), message.eventId());
    }

    void publishDepositDetected(WalletDepositDetectedEventPayload payload) {
        send(
                properties.getKafka().getDepositDetectedTopic(),
                payload.chain() + ":" + payload.txHash(),
                payload,
                "wallet.deposit.detected",
                null
        );
    }

    void publishDepositConfirmed(WalletDepositConfirmedEventPayload payload) {
        send(
                properties.getKafka().getDepositConfirmedTopic(),
                payload.chain() + ":" + payload.txHash(),
                payload,
                "wallet.deposit.confirmed",
                null
        );
    }

    void publishDepositReversed(WalletDepositReversedEventPayload payload) {
        send(
                properties.getKafka().getDepositReversedTopic(),
                payload.chain() + ":" + payload.txHash(),
                payload,
                "wallet.deposit.reversed",
                null
        );
    }

    private void send(String topic, String partitionKey, Object payload, String eventType, String existingEventId) {
        String eventId = existingEventId == null ? "evt-" + idGenerator.nextId() : existingEventId;
        try {
            kafkaTemplate.send(KafkaEventMessageSupport.buildJsonMessage(
                            topic,
                            partitionKey,
                            objectMapper.writeValueAsString(payload),
                            eventId,
                            eventType,
                            "falconx-wallet-service"
                    ))
                    .get(5, TimeUnit.SECONDS);
            log.info("wallet.event.publish.completed topic={} eventType={} eventId={} partitionKey={}",
                    topic,
                    eventType,
                    eventId,
                    partitionKey);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize wallet event payload", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to publish wallet event to Kafka: " + eventType, exception);
        }
    }

    private String resolveTopic(String eventType) {
        return switch (eventType) {
            case "wallet.deposit.detected" -> properties.getKafka().getDepositDetectedTopic();
            case "wallet.deposit.confirmed" -> properties.getKafka().getDepositConfirmedTopic();
            case "wallet.deposit.reversed" -> properties.getKafka().getDepositReversedTopic();
            case "wallet.withdraw.broadcast" -> properties.getKafka().getWithdrawBroadcastTopic();
            case "wallet.withdraw.confirmed" -> properties.getKafka().getWithdrawConfirmedTopic();
            case "wallet.withdraw.failed" -> properties.getKafka().getWithdrawFailedTopic();
            default -> throw new IllegalStateException("Unsupported wallet event type: " + eventType);
        };
    }
}
