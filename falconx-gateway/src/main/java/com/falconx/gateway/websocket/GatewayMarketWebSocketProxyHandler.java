package com.falconx.gateway.websocket;

import com.falconx.gateway.config.GatewayRouteProperties;
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
 * gateway -> market-service 的 WebSocket 代理处理器。
 *
 * <p>该处理器只负责透明桥接消息，不承载市场订阅业务逻辑。
 */
@Component
public class GatewayMarketWebSocketProxyHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayMarketWebSocketProxyHandler.class);

    private final GatewayRouteProperties routeProperties;
    private final WebSocketClient webSocketClient;
    private final GatewayMarketWebSocketSessionRegistry sessionRegistry;
    private final GatewayWebSocketProxyMetrics proxyMetrics;

    public GatewayMarketWebSocketProxyHandler(GatewayRouteProperties routeProperties,
                                              WebSocketClient webSocketClient,
                                              GatewayMarketWebSocketSessionRegistry sessionRegistry,
                                              GatewayWebSocketProxyMetrics proxyMetrics) {
        this.routeProperties = routeProperties;
        this.webSocketClient = webSocketClient;
        this.sessionRegistry = sessionRegistry;
        this.proxyMetrics = proxyMetrics;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        HttpHeaders headers = session.getHandshakeInfo().getHeaders();
        String userId = headers.getFirst("X-User-Id");
        String traceId = TraceIdSupport.reuseOrCreate(headers.getFirst(TraceIdConstants.TRACE_ID_HEADER));
        if (userId == null || userId.isBlank()) {
            return session.close(CloseStatus.POLICY_VIOLATION);
        }

        URI marketWebSocketUri = UriComponentsBuilder.fromUri(routeProperties.getMarketBaseUrl())
                .scheme(resolveWebSocketScheme(routeProperties.getMarketBaseUrl()))
                .replacePath("/ws/v1/market")
                .replaceQuery(null)
                .build(true)
                .toUri();
        HttpHeaders proxyHeaders = new HttpHeaders();
        copyHeader(headers, proxyHeaders, "X-User-Id");
        copyHeader(headers, proxyHeaders, "X-User-Uid");
        copyHeader(headers, proxyHeaders, "X-User-Status");
        copyHeader(headers, proxyHeaders, "X-User-Group-Code");
        copyHeader(headers, proxyHeaders, "X-User-Jti");
        copyHeader(headers, proxyHeaders, TraceIdConstants.TRACE_ID_HEADER);

        withTrace(traceId, () -> log.info("gateway.websocket.proxy.connected sessionId={} userId={} target={}",
                session.getId(),
                userId,
                marketWebSocketUri));

        return webSocketClient.execute(marketWebSocketUri, proxyHeaders, marketSession -> bridge(session, marketSession, traceId))
                .doOnSubscribe(subscription ->
                        proxyMetrics.onBridgeStart(GatewayWebSocketProxyMetrics.UPSTREAM_MARKET))
                .onErrorResume(exception -> {
                    withTrace(traceId, () -> log.error("gateway.websocket.proxy.failed sessionId={} userId={} message={}",
                            session.getId(),
                            userId,
                            exception.getMessage(),
                            exception));
                    return closeQuietly(session, CloseStatus.SERVER_ERROR);
                })
                .doFinally(signalType -> {
                    proxyMetrics.onBridgeEnd(GatewayWebSocketProxyMetrics.UPSTREAM_MARKET);
                    sessionRegistry.release(userId);
                    withTrace(traceId, () -> log.info("gateway.websocket.proxy.closed sessionId={} userId={} signal={}",
                            session.getId(),
                            userId,
                            signalType));
                });
    }

    // package-private 以便单测直接驱动 bridge pipeline 验证转发计数埋点；不改运行时语义。
    Mono<Void> bridge(WebSocketSession clientSession,
                      WebSocketSession marketSession,
                      String traceId) {
        Mono<Void> clientToMarket = marketSession.send(clientSession.receive()
                .flatMap(message -> mapMessage(message, marketSession))
                .doOnNext(forwarded -> proxyMetrics.onMessageForwarded(
                        GatewayWebSocketProxyMetrics.UPSTREAM_MARKET,
                        GatewayWebSocketProxyMetrics.DIRECTION_CLIENT_TO_UPSTREAM)))
                .doFinally(signalType -> closeQuietly(marketSession, CloseStatus.NORMAL).subscribe());

        Mono<Void> marketToClient = clientSession.send(marketSession.receive()
                .flatMap(message -> mapMessage(message, clientSession))
                .doOnNext(forwarded -> proxyMetrics.onMessageForwarded(
                        GatewayWebSocketProxyMetrics.UPSTREAM_MARKET,
                        GatewayWebSocketProxyMetrics.DIRECTION_UPSTREAM_TO_CLIENT)))
                .doFinally(signalType -> closeQuietly(clientSession, CloseStatus.NORMAL).subscribe());

        return Mono.when(clientToMarket, marketToClient)
                .doOnTerminate(() -> withTrace(traceId, () -> log.info("gateway.websocket.proxy.bridge.terminated clientSessionId={} marketSessionId={}",
                        clientSession.getId(),
                        marketSession.getId())));
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
