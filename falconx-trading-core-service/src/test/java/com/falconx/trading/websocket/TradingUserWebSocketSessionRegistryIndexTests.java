package com.falconx.trading.websocket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.falconx.trading.application.TradingAccountSnapshotApplicationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class TradingUserWebSocketSessionRegistryIndexTests {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TradingUserWebSocketSessionRegistry registry =
            new TradingUserWebSocketSessionRegistry(
                    JsonMapper.builder().build(),
                    mock(TradingAccountSnapshotApplicationService.class),
                    meterRegistry);

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    @Test
    void shouldMaintainUserAndAdminSubscriptionIndexesAcrossSubscribeUnsubscribeAndClose() {
        WebSocketSession userSession = session("user-1", "101", null);
        WebSocketSession adminSession = session("admin-1", null, "9001");
        registry.register(userSession);
        registry.register(adminSession);

        registry.handleTextMessage("user-1", subscribe("positions"));
        registry.handleTextMessage("admin-1", subscribe("admin.positions"));

        Assertions.assertEquals(1, registry.indexedUserSubscriptionCount(101L, "positions"));
        Assertions.assertEquals(1, registry.indexedAdminSubscriptionCount("admin.positions"));

        registry.handleTextMessage("user-1", unsubscribe("positions"));
        Assertions.assertEquals(0, registry.indexedUserSubscriptionCount(101L, "positions"));

        registry.unregister("admin-1", CloseStatus.NORMAL, "test-close");
        Assertions.assertEquals(0, registry.indexedAdminSubscriptionCount("admin.positions"));
    }

    @Test
    void shouldExposeWebSocketSessionSubscriptionAndPushQueueMetrics() throws Exception {
        WebSocketSession userSession = session("user-metrics-1", "101", null);
        registry.register(userSession);
        registry.handleTextMessage("user-metrics-1", subscribe("positions"));

        Assertions.assertEquals(1, registry.activeSessionCount());
        Assertions.assertEquals(1, registry.totalIndexedUserSubscriptionCount());
        Assertions.assertEquals(1.0, meterRegistry.get("falconx.trading.websocket.sessions.active")
                .gauge()
                .value());
        Assertions.assertEquals(1.0, meterRegistry.get("falconx.trading.websocket.user.subscriptions.indexed")
                .gauge()
                .value());
        Assertions.assertEquals(0.0, meterRegistry.get("falconx.trading.websocket.admin.subscriptions.indexed")
                .gauge()
                .value());

        CountDownLatch firstSendStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSend = new CountDownLatch(1);
        AtomicInteger sendCount = new AtomicInteger();
        doAnswer(invocation -> {
            if (sendCount.incrementAndGet() == 1) {
                firstSendStarted.countDown();
                releaseFirstSend.await(5, TimeUnit.SECONDS);
            }
            return null;
        }).when(userSession).sendMessage(any(WebSocketMessage.class));

        TradingUserWebSocketEnvelope envelope = new TradingUserWebSocketEnvelope(
                "position.update",
                "positions",
                null,
                Map.of("positionId", "p-1"),
                java.time.OffsetDateTime.now()
        );
        registry.broadcast(101L, "positions", envelope);
        Assertions.assertTrue(firstSendStarted.await(5, TimeUnit.SECONDS));
        registry.broadcast(101L, "positions", envelope);

        Assertions.assertEquals(1, registry.pushQueueSize());
        Assertions.assertEquals(1.0, meterRegistry.get("falconx.trading.websocket.push.queue.size")
                .gauge()
                .value());

        releaseFirstSend.countDown();
    }

    private static WebSocketSession session(String sessionId, String userId, String adminUserId) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(TradingUserWebSocketHandshakeInterceptor.ATTRIBUTE_TRACE_ID, "trace-" + sessionId);
        if (userId != null) {
            attributes.put(TradingUserWebSocketHandshakeInterceptor.ATTRIBUTE_USER_ID, userId);
        }
        if (adminUserId != null) {
            attributes.put(TradingUserWebSocketHandshakeInterceptor.ATTRIBUTE_ADMIN_USER_ID, adminUserId);
        }
        when(session.getId()).thenReturn(sessionId);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static String subscribe(String channel) {
        return "{\"type\":\"subscribe\",\"requestId\":\"sub\",\"channels\":[\"" + channel + "\"]}";
    }

    private static String unsubscribe(String channel) {
        return "{\"type\":\"unsubscribe\",\"requestId\":\"unsub\",\"channels\":[\"" + channel + "\"]}";
    }
}
