package com.falconx.market.producer;

import com.falconx.market.entity.MarketOutboxMessage;

/**
 * market-service Outbox 发件抽象。
 *
 * <p>该接口只供本地 Outbox 调度器使用，
 * 用于把已经认领的低频市场事件真正发送到 Kafka。
 */
public interface MarketOutboxEventPublisher {

    /**
     * 发送一条低频市场发件箱消息。
     *
     * @param message 发件箱消息
     */
    void publish(MarketOutboxMessage message);
}
