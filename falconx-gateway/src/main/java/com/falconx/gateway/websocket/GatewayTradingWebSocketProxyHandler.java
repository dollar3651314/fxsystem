package com.falconx.gateway.websocket;

import com.falconx.gateway.config.GatewayRouteProperties;
import com.falconx.gateway.config.GatewaySecurityProperties;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * gateway -> trading-core-service 的用户交易实时 WebSocket 代理处理器。
 */
@Component
public class GatewayTradingWebSocketProxyHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayTradingWebSocketProxyHandler.class);

    private final GatewayRouteProperties routeProperties;
    private final GatewaySecurityProperties securityProperties;
    private final WebSocketClient webSocketClient;
    private final GatewayMarketWebSocketSessionRegistry sessionRegistry;
    private final GatewayWebSocketProxyMetrics proxyMetrics;

    public GatewayTradingWebSocketProxyHandler(GatewayRouteProperties routeProperties,
                                               GatewaySecurityProperties securityProperties,
                                               WebSocketClient webSocketClient,
                                               GatewayMarketWebSocketSessionRegistry sessionRegistry,
                                               GatewayWebSocketProxyMetrics proxyMetrics) {
        this.routeProperties = routeProperties;
        this.securityProperties = securityProperties;
        this.webSocketClient = webSocketClient;
        this.sessionRegistry = sessionRegistry;
        this.proxyMetrics = proxyMetrics;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        HttpHeaders headers = session.getHandshakeInfo().getHeaders();
        String userId = headers.getFirst("X-User-Id");
        String adminUserId = headers.getFirst("X-Admin-User-Id");
        String traceId = TraceIdSupport.reuseOrCreate(headers.getFirst(TraceIdConstants.TRACE_ID_HEADER));
        // STAGE-2-REALTIME-DATA Phase 2/4：user 与 admin 二选一；都缺失才拒绝
        if ((userId == null || userId.isBlank()) && (adminUserId == null || adminUserId.isBlank())) {
            return session.close(CloseStatus.POLICY_VIOLATION);
        }
        String principalLabel = adminUserId != null && !adminUserId.isBlank() ? "admin:" + adminUserId : "user:" + userId;

        URI tradingWebSocketUri = UriComponentsBuilder.fromUri(routeProperties.getTradingBaseUrl())
                .scheme(resolveWebSocketScheme(routeProperties.getTradingBaseUrl()))
                .replacePath("/ws/v1/trading")
                .replaceQuery(null)
                .build(true)
                .toUri();
        HttpHeaders proxyHeaders = new HttpHeaders();
        copyHeader(headers, proxyHeaders, "X-User-Id");
        copyHeader(headers, proxyHeaders, "X-User-Uid");
        copyHeader(headers, proxyHeaders, "X-User-Status");
        copyHeader(headers, proxyHeaders, "X-User-Group-Code");
        copyHeader(headers, proxyHeaders, "X-User-Jti");
        copyHeader(headers, proxyHeaders, "X-Admin-User-Id");
        copyHeader(headers, proxyHeaders, TraceIdConstants.TRACE_ID_HEADER);
        copyHeader(headers, proxyHeaders, securityProperties.getClientIpHeader());

        withTrace(traceId, () -> log.info("gateway.websocket.proxy.connected sessionId={} principal={} target={}",
                session.getId(),
                principalLabel,
                tradingWebSocketUri));

        return webSocketClient.execute(tradingWebSocketUri, proxyHeaders, tradingSession -> bridge(session, tradingSession, traceId))
                .doOnSubscribe(subscription ->
                        proxyMetrics.onBridgeStart(GatewayWebSocketProxyMetrics.UPSTREAM_TRADING))
                .onErrorResume(exception -> {
                    withTrace(traceId, () -> log.error("gateway.websocket.proxy.failed sessionId={} principal={} message={}",
                            session.getId(),
                            principalLabel,
                            exception.getMessage(),
                            exception));
                    return closeQuietly(session, CloseStatus.SERVER_ERROR);
                })
                .doFinally(signalType -> {
                    proxyMetrics.onBridgeEnd(GatewayWebSocketProxyMetrics.UPSTREAM_TRADING);
                    // user 链路才走连接限流；admin 不计入
                    if (userId != null && !userId.isBlank()) {
                        sessionRegistry.release(userId);
                    }
                    withTrace(traceId, () -> log.info("gateway.websocket.proxy.closed sessionId={} principal={} signal={}",
                            session.getId(),
                            principalLabel,
                            signalType));
                });
    }

    private Mono<Void> bridge(WebSocketSession clientSession,
                              WebSocketSession tradingSession,
                              String traceId) {
        Mono<Void> clientToTrading = tradingSession.send(clientSession.receive()
                .flatMap(message -> mapMessage(message, tradingSession))
                .doOnNext(forwarded -> proxyMetrics.onMessageForwarded(
                        GatewayWebSocketProxyMetrics.UPSTREAM_TRADING,
                        GatewayWebSocketProxyMetrics.DIRECTION_CLIENT_TO_UPSTREAM)))
                .doFinally(signalType -> closeQuietly(tradingSession, CloseStatus.NORMAL).subscribe());

        Mono<Void> tradingToClient = clientSession.send(tradingSession.receive()
                .flatMap(message -> mapMessage(message, clientSession))
                .doOnNext(forwarded -> proxyMetrics.onMessageForwarded(
                        GatewayWebSocketProxyMetrics.UPSTREAM_TRADING,
                        GatewayWebSocketProxyMetrics.DIRECTION_UPSTREAM_TO_CLIENT)))
                .doFinally(signalType -> closeQuietly(clientSession, CloseStatus.NORMAL).subscribe());

        return Mono.when(clientToTrading, tradingToClient)
                .doOnTerminate(() -> withTrace(traceId, () -> log.info("gateway.websocket.proxy.bridge.terminated clientSessionId={} tradingSessionId={}",
                        clientSession.getId(),
                        tradingSession.getId())));
    }

    private Mono<WebSocketMessage> mapMessage(WebSocketMessage source, WebSocketSession targetSession) {
        return switch (source.getType()) {
            case TEXT -> Mono.just(targetSession.textMessage(source.getPayloadAsText()));
            case PING -> Mono.just(targetSession.pingMessage(factory -> factory.wrap(new byte[0])));
            case PONG -> Mono.just(targetSession.pongMessage(factory -> factory.wrap(new byte[0])));
            default -> Mono.empty();
        };
    }

    private Mono<Void> closeQuietly(WebSocketSession session, CloseStatus closeStatus) {
        if (!session.isOpen()) {
            return Mono.empty();
        }
        return session.close(closeStatus).onErrorResume(exception -> Mono.empty());
    }

    private void copyHeader(HttpHeaders source, HttpHeaders target, String name) {
        String value = source.getFirst(name);
        if (value != null && !value.isBlank()) {
            target.set(name, value);
        }
    }

    private String resolveWebSocketScheme(URI baseUri) {
        return "https".equalsIgnoreCase(baseUri.getScheme()) ? "wss" : "ws";
    }

    private void withTrace(String traceId, Runnable action) {
        MDC.put(TraceIdConstants.TRACE_ID_MDC_KEY, traceId);
        try {
            action.run();
        } finally {
            MDC.remove(TraceIdConstants.TRACE_ID_MDC_KEY);
        }
    }
}
