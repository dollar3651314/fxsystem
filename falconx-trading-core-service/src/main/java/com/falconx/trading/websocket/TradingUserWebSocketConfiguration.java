package com.falconx.trading.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * trading-core-service 用户交易实时 WebSocket 配置。
 */
@Configuration
@EnableWebSocket
public class TradingUserWebSocketConfiguration implements WebSocketConfigurer {

    private final TradingUserWebSocketHandler tradingUserWebSocketHandler;
    private final TradingUserWebSocketHandshakeInterceptor handshakeInterceptor;

    public TradingUserWebSocketConfiguration(TradingUserWebSocketHandler tradingUserWebSocketHandler,
                                             TradingUserWebSocketHandshakeInterceptor handshakeInterceptor) {
        this.tradingUserWebSocketHandler = tradingUserWebSocketHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(tradingUserWebSocketHandler, "/ws/v1/trading")
                .addInterceptors(handshakeInterceptor);
    }
}
