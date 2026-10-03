package com.falconx.trading.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 用户交易实时 WebSocket 处理器。
 */
@Component
public class TradingUserWebSocketHandler extends TextWebSocketHandler {

    private final TradingUserWebSocketSessionRegistry sessionRegistry;

    public TradingUserWebSocketHandler(TradingUserWebSocketSessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionRegistry.register(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        sessionRegistry.handleTextMessage(session.getId(), message.getPayload());
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        sessionRegistry.markPong(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionRegistry.unregister(session.getId(), status, "client-closed");
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessionRegistry.unregister(session.getId(), CloseStatus.SERVER_ERROR, exception.getMessage());
    }
}
