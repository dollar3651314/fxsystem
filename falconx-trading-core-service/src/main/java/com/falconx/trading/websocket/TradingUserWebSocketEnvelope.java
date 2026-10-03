package com.falconx.trading.websocket;

import java.time.OffsetDateTime;

/**
 * 用户交易实时 WebSocket 统一消息信封。
 */
public record TradingUserWebSocketEnvelope(
        String type,
        String channel,
        String requestId,
        Object data,
        OffsetDateTime ts
) {
}
