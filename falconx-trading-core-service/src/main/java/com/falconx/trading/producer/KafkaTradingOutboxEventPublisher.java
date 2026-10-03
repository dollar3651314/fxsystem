package com.falconx.trading.producer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.falconx.infrastructure.kafka.KafkaEventMessageSupport;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.entity.TradingOutboxMessage;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 交易核心 Outbox 的 Kafka 发布实现。
 *
 * <p>该实现用于把已经由 `TradingOutboxDispatcher` 认领的事件真正发送到 Kafka。
 * 由于调度器只有在 `publish` 成功返回后才会把消息标记为 `SENT`，
 * 所以这里显式等待 Kafka 发送完成，再把成功 / 失败语义返回给上层状态机。
 */
@Component
public class KafkaTradingOutboxEventPublisher implements TradingOutboxEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaTradingOutboxEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final TradingCoreServiceProperties properties;

    public KafkaTradingOutboxEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                            ObjectMapper objectMapper,
                                            TradingCoreServiceProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publish(TradingOutboxMessage message) {
        String topic = resolveTopic(message.eventType());
        String payloadJson = toJson(message.payload());
        try {
            kafkaTemplate.send(KafkaEventMessageSupport.buildJsonMessage(
                            topic,
                            message.partitionKey(),
                            payloadJson,
                            message.eventId(),
                            message.eventType(),
                            "falconx-trading-core-service"
                    ))
                    .get(5, TimeUnit.SECONDS);
            log.info("trading.outbox.dispatch.completed topic={} eventId={} eventType={} partitionKey={}",
                    topic,
                    message.eventId(),
                    message.eventType(),
                    message.partitionKey());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to publish trading outbox event to Kafka: " + message.eventType(),
                    exception);
        }
    }

    private String resolveTopic(String eventType) {
        if ("trading.deposit.credited".equals(eventType)) {
            return properties.getKafka().getDepositCreditedTopic();
        }
        return "falconx." + eventType;
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize trading outbox payload", exception);
        }
    }
}
