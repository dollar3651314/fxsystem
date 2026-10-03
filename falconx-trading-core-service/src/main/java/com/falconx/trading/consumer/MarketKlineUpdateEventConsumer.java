package com.falconx.trading.consumer;

import com.falconx.market.contract.event.MarketKlineUpdateEventPayload;
import com.falconx.trading.application.TradingMarketKlineUpdateApplicationService;
import org.springframework.stereotype.Component;

/**
 * 收盘 K 线事件消费者。
 *
 * <p>该消费者承接 `market.kline.update` 的低频正式消费，
 * 当前阶段只负责把事件写入 trading owner 的 Inbox 审计事实。
 */
@Component
public class MarketKlineUpdateEventConsumer {

    private final TradingMarketKlineUpdateApplicationService tradingMarketKlineUpdateApplicationService;

    public MarketKlineUpdateEventConsumer(
            TradingMarketKlineUpdateApplicationService tradingMarketKlineUpdateApplicationService
    ) {
        this.tradingMarketKlineUpdateApplicationService = tradingMarketKlineUpdateApplicationService;
    }

    /**
     * 消费一条收盘 K 线事件。
     *
     * @param eventId 事件 ID
     * @param payload K 线 payload
     * @param payloadJson 原始 JSON
     */
    public void consume(String eventId, MarketKlineUpdateEventPayload payload, String payloadJson) {
        tradingMarketKlineUpdateApplicationService.recordConsumed(
                eventId,
                payload.symbol(),
                payload.interval(),
                payload.closeTime(),
                payloadJson
        );
    }
}
