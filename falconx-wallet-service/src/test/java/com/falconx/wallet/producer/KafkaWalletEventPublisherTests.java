package com.falconx.wallet.producer;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.falconx.domain.enums.ChainType;
import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.infrastructure.kafka.KafkaEventHeaderConstants;
import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.contract.event.WalletDepositConfirmedEventPayload;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * `KafkaWalletEventPublisher` 单元测试。
 *
 * <p>该测试用于确认钱包事件已经走真实 Kafka 发送链路。
 */
class KafkaWalletEventPublisherTests {

    @Test
    void shouldPublishConfirmedDepositToKafkaTemplate() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(Message.class))).thenReturn(CompletableFuture.completedFuture(null));

        KafkaWalletEventPublisher publisher = new KafkaWalletEventPublisher(
                new WalletServiceProperties(),
                kafkaTemplate,
                JsonMapper.builder().build(),
                fixedIdGenerator()
        );

        publisher.publishDepositConfirmed(new WalletDepositConfirmedEventPayload(
                70001L,
                9001L,
                ChainType.ETH,
                "USDT",
                "0xabc",
                "0xfrom",
                "0xto",
                new BigDecimal("125.50"),
                12,
                12,
                OffsetDateTime.parse("2026-04-17T12:00:00Z")
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<String>> captor = ArgumentCaptor.forClass(Message.class);
        verify(kafkaTemplate).send(captor.capture());
        Message<String> message = captor.getValue();
        Assertions.assertEquals("falconx.wallet.deposit.confirmed", message.getHeaders().get("kafka_topic"));
        Assertions.assertEquals("ETH:0xabc", message.getHeaders().get("kafka_messageKey"));
        Assertions.assertEquals("evt-20001", message.getHeaders().get(KafkaEventHeaderConstants.EVENT_ID_HEADER));
        Assertions.assertTrue(message.getPayload().contains("\"txHash\":\"0xabc\""));
    }

    private IdGenerator fixedIdGenerator() {
        return () -> 20001L;
    }
}
