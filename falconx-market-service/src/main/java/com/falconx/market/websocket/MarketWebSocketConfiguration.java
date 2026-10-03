package com.falconx.market.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * market-service WebSocket 配置。
 *
 * <p>该端点由 gateway 代理对外，market-service 只负责订阅协议和推送逻辑。
 */
@Configuration
@EnableWebSocket
public class MarketWebSocketConfiguration implements WebSocketConfigurer {

    private final MarketWebSocketHandler marketWebSocketHandler;
    private final MarketWebSocketHandshakeInterceptor handshakeInterceptor;

    public MarketWebSocketConfiguration(MarketWebSocketHandler marketWebSocketHandler,
                                        MarketWebSocketHandshakeInterceptor handshakeInterceptor) {
        this.marketWebSocketHandler = marketWebSocketHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(marketWebSocketHandler, "/ws/v1/market")
                .addInterceptors(handshakeInterceptor);
    }
}
