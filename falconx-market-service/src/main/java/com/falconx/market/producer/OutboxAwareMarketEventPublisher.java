package com.falconx.market.producer;

import com.falconx.market.contract.event.MarketKlineUpdateEventPayload;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.market.entity.MarketOutboxMessage;
import com.falconx.market.entity.MarketOutboxStatus;
import com.falconx.market.repository.MarketOutboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 兼顾高频直发与低频 Outbox 的市场事件发布实现。
 *
 * <p>该实现固定遵循两条规则：
 *
 * <ul>
 *   <li>`market.price.tick` 高频事件直接发 Kafka</li>
 *   <li>`market.kline.update` 低频关键事件先写 `t_outbox`，再由调度器异步投递</li>
 * </ul>
 */
@Component
@Primary
public class OutboxAwareMarketEventPublisher implements MarketEventPublisher {

    private final KafkaMarketEventPublisher kafkaMarketEventPublisher;
    private final MarketOutboxRepository marketOutboxRepository;

    public OutboxAwareMarketEventPublisher(KafkaMarketEventPublisher kafkaMarketEventPublisher,
                                           MarketOutboxRepository marketOutboxRepository) {
        this.kafkaMarketEventPublisher = kafkaMarketEventPublisher;
        this.marketOutboxRepository = marketOutboxRepository;
    }

    @Override
    public void publishPriceTick(MarketPriceTickEventPayload payload) {
        kafkaMarketEventPublisher.publishPriceTick(payload);
    }

    @Override
    public void publishKlineUpdate(MarketKlineUpdateEventPayload payload) {
        marketOutboxRepository.save(new MarketOutboxMessage(
                null,
                stableEventId(payload),
                "market.kline.update",
                payload.symbol() + ":" + payload.interval(),
                payload,
                MarketOutboxStatus.PENDING,
                occurredAt(payload),
                null,
                0,
                occurredAt(payload),
                null
        ));
    }

    private String stableEventId(MarketKlineUpdateEventPayload payload) {
        String key = payload.symbol() + ":" + payload.interval() + ":" + payload.closeTime();
        return "market-kline:" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private OffsetDateTime occurredAt(MarketKlineUpdateEventPayload payload) {
        return payload.closeTime() == null ? OffsetDateTime.now() : payload.closeTime();
    }
}
