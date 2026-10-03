package com.falconx.gateway.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link GatewayWebSocketProxyMetrics} bridge 级指标单测。
 *
 * <p>覆盖：
 * <ul>
 *   <li>active gauge：onBridgeStart→1，onBridgeEnd→0（含异常路径下 doFinally 仍 decrement）；
 *       end 超过 start 不会出现负值。</li>
 *   <li>messages counter：按 upstream + direction 维度计数 N 帧后 count==N。</li>
 *   <li>MeterRegistry 为 null 时埋点静默不抛（未配置 / 测试环境守卫）。</li>
 *   <li>驱动真实 handler.bridge(...) pipeline，确认 doOnNext 转发计数挂在真实响应式链上。</li>
 * </ul>
 */
class GatewayWebSocketProxyMetricsTests {

    private static final String SESSIONS_ACTIVE = "falconx.gateway.websocket.proxy.sessions.active";
    private static final String MESSAGES_TOTAL = "falconx.gateway.websocket.proxy.messages.total";

    @Test
    void activeGaugeReflectsBridgeStartAndEndPerUpstream() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayWebSocketProxyMetrics metrics = new GatewayWebSocketProxyMetrics(registry);

        assertThat(activeGauge(registry, GatewayWebSocketProxyMetrics.UPSTREAM_MARKET)).isZero();

        metrics.onBridgeStart(GatewayWebSocketProxyMetrics.UPSTREAM_MARKET);
        assertThat(activeGauge(registry, GatewayWebSocketProxyMetrics.UPSTREAM_MARKET)).isEqualTo(1.0d);
        // trading 维度独立，不受 market 影响
        assertThat(activeGauge(registry, GatewayWebSocketProxyMetrics.UPSTREAM_TRADING)).isZero();

        metrics.onBridgeEnd(GatewayWebSocketProxyMetrics.UPSTREAM_MARKET);
        assertThat(activeGauge(registry, GatewayWebSocketProxyMetrics.UPSTREAM_MARKET)).isZero();
    }

    @Test
    void onBridgeEndNeverGoesNegativeEvenIfUnbalanced() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayWebSocketProxyMetrics metrics = new GatewayWebSocketProxyMetrics(registry);

        // 多余的 end（如重复 doFinally）不得使 gauge 变负
        metrics.onBridgeEnd(GatewayWebSocketProxyMetrics.UPSTREAM_TRADING);
        metrics.onBridgeEnd(GatewayWebSocketProxyMetrics.UPSTREAM_TRADING);

        assertThat(metrics.activeBridges(GatewayWebSocketProxyMetrics.UPSTREAM_TRADING)).isZero();
        assertThat(activeGauge(registry, GatewayWebSocketProxyMetrics.UPSTREAM_TRADING)).isZero();
    }

    @Test
    void messagesCounterCountsPerUpstreamAndDirection() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayWebSocketProxyMetrics metrics = new GatewayWebSocketProxyMetrics(registry);

        for (int i = 0; i < 3; i++) {
            metrics.onMessageForwarded(GatewayWebSocketProxyMetrics.UPSTREAM_MARKET,
                    GatewayWebSocketProxyMetrics.DIRECTION_CLIENT_TO_UPSTREAM);
        }
        for (int i = 0; i < 5; i++) {
            metrics.onMessageForwarded(GatewayWebSocketProxyMetrics.UPSTREAM_TRADING,
                    GatewayWebSocketProxyMetrics.DIRECTION_UPSTREAM_TO_CLIENT);
        }

        assertThat(messageCount(registry, GatewayWebSocketProxyMetrics.UPSTREAM_MARKET,
                GatewayWebSocketProxyMetrics.DIRECTION_CLIENT_TO_UPSTREAM)).isEqualTo(3.0d);
        assertThat(messageCount(registry, GatewayWebSocketProxyMetrics.UPSTREAM_MARKET,
                GatewayWebSocketProxyMetrics.DIRECTION_UPSTREAM_TO_CLIENT)).isZero();
        assertThat(messageCount(registry, GatewayWebSocketProxyMetrics.UPSTREAM_TRADING,
                GatewayWebSocketProxyMetrics.DIRECTION_UPSTREAM_TO_CLIENT)).isEqualTo(5.0d);
    }

    @Test
    void nullMeterRegistryIsGuardedAndDoesNotThrow() {
        GatewayWebSocketProxyMetrics metrics = new GatewayWebSocketProxyMetrics((io.micrometer.core.instrument.MeterRegistry) null);

        // 不应抛出 NPE；gauge 内部 AtomicInteger 仍可计数（仅未注册到 registry）
        metrics.onBridgeStart(GatewayWebSocketProxyMetrics.UPSTREAM_MARKET);
        metrics.onMessageForwarded(GatewayWebSocketProxyMetrics.UPSTREAM_MARKET,
                GatewayWebSocketProxyMetrics.DIRECTION_CLIENT_TO_UPSTREAM);
        metrics.onBridgeEnd(GatewayWebSocketProxyMetrics.UPSTREAM_MARKET);

        assertThat(metrics.activeBridges(GatewayWebSocketProxyMetrics.UPSTREAM_MARKET)).isZero();
    }

    @Test
    void bridgePipelineForwardsAndCountsBothDirections() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayWebSocketProxyMetrics metrics = new GatewayWebSocketProxyMetrics(registry);
        GatewayMarketWebSocketProxyHandler handler = new GatewayMarketWebSocketProxyHandler(
                null, null, null, metrics);

        // client→market 2 帧 TEXT，market→client 1 帧 TEXT
        WebSocketSession clientSession = mockSession(2);
        WebSocketSession marketSession = mockSession(1);

        handler.bridge(clientSession, marketSession, "trace-test").block();

        assertThat(messageCount(registry, GatewayWebSocketProxyMetrics.UPSTREAM_MARKET,
                GatewayWebSocketProxyMetrics.DIRECTION_CLIENT_TO_UPSTREAM)).isEqualTo(2.0d);
        assertThat(messageCount(registry, GatewayWebSocketProxyMetrics.UPSTREAM_MARKET,
                GatewayWebSocketProxyMetrics.DIRECTION_UPSTREAM_TO_CLIENT)).isEqualTo(1.0d);
    }

    @SuppressWarnings("unchecked")
    private WebSocketSession mockSession(int receiveFrameCount) {
        WebSocketSession session = mock(WebSocketSession.class);
        WebSocketMessage textMessage = mock(WebSocketMessage.class);
        when(textMessage.getType()).thenReturn(WebSocketMessage.Type.TEXT);
        when(textMessage.getPayloadAsText()).thenReturn("{}");
        when(session.textMessage(any())).thenReturn(textMessage);
        // receive() 提供 N 个 TEXT 帧后完成
        when(session.receive()).thenReturn(Flux.range(0, receiveFrameCount).map(i -> textMessage));
        // send(...) 订阅入参并完成（不真正发送）
        when(session.send(any())).thenAnswer(invocation -> {
            org.reactivestreams.Publisher<WebSocketMessage> source =
                    invocation.getArgument(0, org.reactivestreams.Publisher.class);
            return Flux.from(source).then();
        });
        when(session.isOpen()).thenReturn(false);
        when(session.close(any())).thenReturn(Mono.empty());
        when(session.getId()).thenReturn("session-" + System.identityHashCode(session));
        return session;
    }

    private double activeGauge(SimpleMeterRegistry registry, String upstream) {
        return registry.get(SESSIONS_ACTIVE).tag("upstream", upstream).gauge().value();
    }

    private double messageCount(SimpleMeterRegistry registry, String upstream, String direction) {
        return registry.get(MESSAGES_TOTAL)
                .tag("upstream", upstream)
                .tag("direction", direction)
                .counter()
                .count();
    }
}
