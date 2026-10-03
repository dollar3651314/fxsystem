package com.falconx.trading.producer;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.falconx.infrastructure.kafka.KafkaEventHeaderConstants;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.entity.TradingOutboxMessage;
import com.falconx.trading.entity.TradingOutboxStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.Message;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * `KafkaTradingOutboxEventPublisher` 单元测试。
 *
 * <p>该测试用于确认交易核心 Outbox 已经接到真实 Kafka producer。
 */
class KafkaTradingOutboxEventPublisherTests {

    @Test
    void shouldPublishDepositCreditedOutboxMessageToKafka() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(Message.class))).thenReturn(CompletableFuture.completedFuture(null));

        KafkaTradingOutboxEventPublisher publisher = new KafkaTradingOutboxEventPublisher(
                kafkaTemplate,
                JsonMapper.builder().build(),
                new TradingCoreServiceProperties()
        );

        publisher.publish(new TradingOutboxMessage(
                "outbox-1",
                "evt-30001",
                "trading.deposit.credited",
                "deposit-credited:ETH:0xabc",
                Map.of("depositId", 1, "userId", 2),
                TradingOutboxStatus.DISPATCHING,
                OffsetDateTime.parse("2026-04-17T12:00:00Z"),
                null,
                0,
                null,
                null
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(kafkaTemplate).send(captor.capture());
        Message<String> message = captor.getValue();
        Assertions.assertEquals("falconx.trading.deposit.credited", message.getHeaders().get("kafka_topic"));
        Assertions.assertEquals("deposit-credited:ETH:0xabc", message.getHeaders().get("kafka_messageKey"));
        Assertions.assertEquals("evt-30001", message.getHeaders().get(KafkaEventHeaderConstants.EVENT_ID_HEADER));
        Assertions.assertTrue(message.getPayload().contains("\"depositId\":1"));
    }

    @Test
    void shouldPublishSwapSettledOutboxMessageToKafka() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(Message.class))).thenReturn(CompletableFuture.completedFuture(null));

        KafkaTradingOutboxEventPublisher publisher = new KafkaTradingOutboxEventPublisher(
                kafkaTemplate,
                JsonMapper.builder().build(),
                new TradingCoreServiceProperties()
        );

        publisher.publish(new TradingOutboxMessage(
                "outbox-2",
                "evt-30002",
                "trading.swap.settled",
                "30002",
                Map.of("ledgerId", 1, "positionId", 2, "settlementType", "SWAP_INCOME"),
                TradingOutboxStatus.DISPATCHING,
                OffsetDateTime.parse("2026-04-21T12:00:00Z"),
                null,
                0,
                null,
                null
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(kafkaTemplate).send(captor.capture());
        Message<String> message = captor.getValue();
        Assertions.assertEquals("falconx.trading.swap.settled", message.getHeaders().get("kafka_topic"));
        Assertions.assertEquals("30002", message.getHeaders().get("kafka_messageKey"));
        Assertions.assertEquals("evt-30002", message.getHeaders().get(KafkaEventHeaderConstants.EVENT_ID_HEADER));
        Assertions.assertTrue(message.getPayload().contains("\"settlementType\":\"SWAP_INCOME\""));
    }
}
