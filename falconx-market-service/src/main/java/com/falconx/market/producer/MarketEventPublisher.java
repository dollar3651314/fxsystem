package com.falconx.market.producer;

import com.falconx.market.contract.event.MarketKlineUpdateEventPayload;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;

/**
 * 市场事件发布抽象。
 *
 * <p>该接口用于冻结高频价格事件和低频 K 线事件的发布点。
 * 后续无论底层是 Kafka、测试桩还是别的消息总线，都只能通过该接口从应用层向外发送事件。
 */
public interface MarketEventPublisher {

    /**
     * 发布高频价格事件。
     *
     * @param payload 价格事件 payload
     */
    void publishPriceTick(MarketPriceTickEventPayload payload);

    /**
     * 发布 K 线事件。
     *
     * @param payload K 线事件 payload
     */
    void publishKlineUpdate(MarketKlineUpdateEventPayload payload);
}
