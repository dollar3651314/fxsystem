package com.falconx.market.producer;

import com.falconx.market.contract.event.MarketKlineUpdateEventPayload;
import com.falconx.market.entity.MarketOutboxMessage;
import com.falconx.market.repository.MarketOutboxRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Outbox 感知型市场事件发布测试。
 *
 * <p>该测试验证 `market.kline.update` 已经不再由应用层直接同步发 Kafka，
 * 而是先写入 market owner 的 `t_outbox`。
 */
class OutboxAwareMarketEventPublisherTests {

    @Test
    void shouldEnqueueKlineUpdateIntoOutboxInsteadOfPublishingDirectly() {
        KafkaMarketEventPublisher kafkaPublisher = mock(KafkaMarketEventPublisher.class);
        MarketOutboxRepository outboxRepository = mock(MarketOutboxRepository.class);
        OutboxAwareMarketEventPublisher publisher = new OutboxAwareMarketEventPublisher(kafkaPublisher, outboxRepository);

        publisher.publishKlineUpdate(new MarketKlineUpdateEventPayload(
                "BTCUSDT",
                "1m",
                new BigDecimal("100"),
                new BigDecimal("102"),
                new BigDecimal("99"),
                new BigDecimal("101"),
                BigDecimal.ZERO,
                OffsetDateTime.parse("2026-04-17T12:00:00Z"),
                OffsetDateTime.parse("2026-04-17T12:00:59Z"),
                true
        ));

        ArgumentCaptor<MarketOutboxMessage> captor = ArgumentCaptor.forClass(MarketOutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        verify(kafkaPublisher, never()).publishKlineUpdate(any(MarketKlineUpdateEventPayload.class));
        Assertions.assertEquals("market.kline.update", captor.getValue().eventType());
        Assertions.assertEquals("BTCUSDT:1m", captor.getValue().partitionKey());
    }
}
