package com.falconx.market.websocket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.falconx.market.config.MarketServiceProperties;
import com.falconx.market.entity.MarketSymbolWithSpec;
import com.falconx.market.repository.MarketLatestQuoteRepository;
import com.falconx.market.repository.MarketReferenceQuoteRepository;
import com.falconx.market.repository.MarketSymbolRepository;
import com.falconx.market.service.MarketGroupMarkupService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class MarketWebSocketSessionRegistryIndexTests {

    private final MarketSymbolRepository symbolRepository = mock(MarketSymbolRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MarketWebSocketSessionRegistry registry = new MarketWebSocketSessionRegistry(
            JsonMapper.builder().build(),
            symbolRepository,
            mock(MarketLatestQuoteRepository.class),
            mock(MarketReferenceQuoteRepository.class),
            mock(MarketGroupMarkupService.class),
            new MarketServiceProperties(),
            meterRegistry
    );

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    @Test
    void shouldMaintainChannelSymbolIndexAcrossSubscribeUnsubscribeAndClose() {
        when(symbolRepository.findTradingSymbolsByGroupCode("default"))
                .thenReturn(List.of(symbol("EURUSD"), symbol("USDJPY")));
        WebSocketSession session = session("market-1");
        registry.register(session);

        registry.handleTextMessage("market-1",
                "{\"type\":\"subscribe\",\"requestId\":\"sub\",\"channels\":[\"price.tick\"],\"symbols\":[\"EURUSD\"]}");

        Assertions.assertEquals(1, registry.indexedSubscriptionCount("price.tick", "EURUSD"));
        Assertions.assertEquals(0, registry.indexedSubscriptionCount("price.tick", "USDJPY"));

        registry.handleTextMessage("market-1",
                "{\"type\":\"unsubscribe\",\"requestId\":\"unsub\",\"channels\":[\"price.tick\"],\"symbols\":[\"EURUSD\"]}");
        Assertions.assertEquals(0, registry.indexedSubscriptionCount("price.tick", "EURUSD"));

        registry.handleTextMessage("market-1",
                "{\"type\":\"subscribe\",\"requestId\":\"sub2\",\"channels\":[\"price.tick\"],\"symbols\":[\"EURUSD\"]}");
        registry.unregister("market-1", CloseStatus.NORMAL, "test-close");
        Assertions.assertEquals(0, registry.indexedSubscriptionCount("price.tick", "EURUSD"));
    }

    @Test
    void shouldExposeWebSocketSessionAndSubscriptionMetrics() {
        when(symbolRepository.findTradingSymbolsByGroupCode("default"))
                .thenReturn(List.of(symbol("EURUSD")));
        WebSocketSession session = session("market-metrics-1");
        registry.register(session);

        Assertions.assertEquals(1, registry.activeSessionCount());
        Assertions.assertEquals(1.0, meterRegistry.get("falconx.market.websocket.sessions.active")
                .gauge()
                .value());

        registry.handleTextMessage("market-metrics-1",
                "{\"type\":\"subscribe\",\"requestId\":\"sub\",\"channels\":[\"price.tick\"],\"symbols\":[\"EURUSD\"]}");

        Assertions.assertEquals(1, registry.totalIndexedSubscriptionCount());
        Assertions.assertEquals(1.0, meterRegistry.get("falconx.market.websocket.subscriptions.indexed")
                .gauge()
                .value());
        Assertions.assertEquals(0.0, meterRegistry.get("falconx.market.websocket.buffer.bytes")
                .gauge()
                .value());

        registry.unregister("market-metrics-1", CloseStatus.NORMAL, "test-close");
        Assertions.assertEquals(0.0, meterRegistry.get("falconx.market.websocket.sessions.active")
                .gauge()
                .value());
        Assertions.assertEquals(0.0, meterRegistry.get("falconx.market.websocket.subscriptions.indexed")
                .gauge()
                .value());
    }

    private static WebSocketSession session(String sessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(MarketWebSocketHandshakeInterceptor.ATTRIBUTE_TRACE_ID, "trace-" + sessionId);
        attributes.put(MarketWebSocketHandshakeInterceptor.ATTRIBUTE_GROUP_CODE, "default");
        when(session.getId()).thenReturn(sessionId);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static MarketSymbolWithSpec symbol(String symbol) {
        return new MarketSymbolWithSpec(
                1L,
                symbol,
                1,
                "FX",
                symbol.substring(0, 3),
                symbol.substring(3),
                5,
                2,
                new BigDecimal("0.01"),
                new BigDecimal("1000"),
                new BigDecimal("1"),
                100,
                new BigDecimal("0.0005"),
                BigDecimal.ZERO,
                1
        );
    }
}
