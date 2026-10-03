package com.falconx.identity.consumer;

import tools.jackson.databind.ObjectMapper;
import com.falconx.identity.config.IdentityServiceProperties;
import com.falconx.infrastructure.kafka.KafkaEventHeaderConstants;
import com.falconx.trading.contract.event.DepositCreditedEventPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * identity-service Kafka 监听适配器。
 *
 * <p>该组件负责把 `falconx.trading.deposit.credited` Kafka 事件适配为
 * `DepositCreditedEventConsumer` 可以直接处理的领域调用。
 */
@Component
public class IdentityKafkaEventListener {

    private static final Logger log = LoggerFactory.getLogger(IdentityKafkaEventListener.class);

    private final ObjectMapper objectMapper;
    private final IdentityServiceProperties properties;
    private final DepositCreditedEventConsumer depositCreditedEventConsumer;
    private final IdentityManagedKafkaExecutionSupport identityManagedKafkaExecutionSupport;

    public IdentityKafkaEventListener(ObjectMapper objectMapper,
                                      IdentityServiceProperties properties,
                                      DepositCreditedEventConsumer depositCreditedEventConsumer,
                                      IdentityManagedKafkaExecutionSupport identityManagedKafkaExecutionSupport) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.depositCreditedEventConsumer = depositCreditedEventConsumer;
        this.identityManagedKafkaExecutionSupport = identityManagedKafkaExecutionSupport;
    }

    /**
     * 消费交易核心的入账完成事件。
     *
     * @param payloadJson Kafka 里的 JSON payload
     * @param eventId 事件 ID
     * @param traceId Kafka 透传的 traceId
     */
    @KafkaListener(
            topics = "${falconx.identity.kafka.deposit-credited-topic}",
            groupId = "${falconx.identity.kafka.consumer-group-id}"
    )
    public void onDepositCredited(String payloadJson,
                                  @Header(KafkaEventHeaderConstants.EVENT_ID_HEADER) String eventId,
                                  @Header(value = KafkaEventHeaderConstants.TRACE_ID_HEADER, required = false)
                                  String traceId) {
        identityManagedKafkaExecutionSupport.execute(traceId, () -> {
            log.info("identity.kafka.consume.received topic={} eventId={}",
                    properties.getKafka().getDepositCreditedTopic(),
                    eventId);
            depositCreditedEventConsumer.handle(
                    eventId,
                    objectMapper.readValue(payloadJson, DepositCreditedEventPayload.class)
            );
        });
    }
}
