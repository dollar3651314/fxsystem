package com.falconx.trading.producer;

import com.falconx.trading.entity.TradingOutboxMessage;

/**
 * 交易核心 Outbox 事件发布器抽象。
 *
 * <p>该接口把“调度器如何取批、如何处理重试”与“事件究竟发到哪里”分开。
 * 这样无论底层使用 Kafka、测试替身还是别的消息总线，
 * `TradingOutboxDispatcher` 都不需要感知具体实现细节。
 */
public interface TradingOutboxEventPublisher {

    /**
     * 发布一条 Outbox 事件。
     *
     * @param message 已被调度器认领的 Outbox 记录
     */
    void publish(TradingOutboxMessage message);
}
