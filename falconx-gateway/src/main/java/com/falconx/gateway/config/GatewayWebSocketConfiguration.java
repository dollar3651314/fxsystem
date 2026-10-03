package com.falconx.gateway.config;

import com.falconx.gateway.websocket.GatewayMarketWebSocketProxyHandler;
import com.falconx.gateway.websocket.GatewayTradingWebSocketProxyHandler;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

/**
 * gateway 北向 WebSocket 配置。
 *
 * <p>当前冻结 `/ws/v1/market` 行情端点和 `/ws/v1/trading` 用户交易实时端点，
 * 由 gateway 负责握手鉴权、连接数限制和服务内代理转发。
 */
@Configuration
public class GatewayWebSocketConfiguration {

    @Bean
    public HandlerMapping gatewayWebSocketHandlerMapping(GatewayMarketWebSocketProxyHandler marketProxyHandler,
                                                         GatewayTradingWebSocketProxyHandler tradingProxyHandler) {
        SimpleUrlHandlerMapping handlerMapping = new SimpleUrlHandlerMapping();
        handlerMapping.setOrder(-1);
        handlerMapping.setUrlMap(Map.<String, WebSocketHandler>of(
                "/ws/v1/market",
                marketProxyHandler,
                "/ws/v1/trading",
                tradingProxyHandler
        ));
        return handlerMapping;
    }

    @Bean
    public WebSocketHandlerAdapter gatewayWebSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
