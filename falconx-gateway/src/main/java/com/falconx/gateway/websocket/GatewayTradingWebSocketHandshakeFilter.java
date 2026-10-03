package com.falconx.gateway.websocket;

import com.falconx.gateway.config.GatewaySecurityProperties;
import com.falconx.gateway.security.GatewayAuthenticatedPrincipal;
import com.falconx.gateway.security.GatewayJwtVerifier;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * `/ws/v1/trading` 用户交易实时推送握手过滤器。
 */
@Component
public class GatewayTradingWebSocketHandshakeFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayTradingWebSocketHandshakeFilter.class);
    private static final String TRADING_WS_PATH = "/ws/v1/trading";

    private final GatewayJwtVerifier gatewayJwtVerifier;
    private final GatewaySecurityProperties securityProperties;
    private final GatewayMarketWebSocketSessionRegistry sessionRegistry;

    public GatewayTradingWebSocketHandshakeFilter(GatewayJwtVerifier gatewayJwtVerifier,
                                                  GatewaySecurityProperties securityProperties,
                                                  GatewayMarketWebSocketSessionRegistry sessionRegistry) {
        this.gatewayJwtVerifier = gatewayJwtVerifier;
        this.securityProperties = securityProperties;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isTradingWebSocketHandshake(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        String traceId = TraceIdSupport.newTraceId();
        exchange.getResponse().getHeaders().set(TraceIdConstants.TRACE_ID_HEADER, traceId);
        String clientIp = resolveClientIp(exchange.getRequest());
        String token = exchange.getRequest().getQueryParams().getFirst("token");

        withTrace(traceId, () -> log.info("gateway.websocket.handshake.received path={} clientIp={}",
                exchange.getRequest().getPath().value(),
                clientIp));

        if (token == null || token.isBlank()) {
            withTrace(traceId, () -> log.warn("gateway.websocket.handshake.rejected path={} reason=missing_token clientIp={}",
                    exchange.getRequest().getPath().value(),
                    clientIp));
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }

        // STAGE-2-REALTIME-DATA Phase 2：识别 admin token（iss=falconx-console-service），分流到 admin 链路
        if (gatewayJwtVerifier.looksLikeAdminToken(token)) {
            return gatewayJwtVerifier.verifyAdminAccessToken(token)
                    .flatMap(adminUserId -> continueAdminHandshake(exchange, chain, traceId, clientIp, adminUserId))
                    .onErrorResume(IllegalStateException.class, exception -> {
                        withTrace(traceId, () -> log.warn("gateway.websocket.handshake.rejected path={} reason=invalid_admin_token clientIp={} message={}",
                                exchange.getRequest().getPath().value(),
                                clientIp,
                                exception.getMessage()));
                        return reject(exchange, HttpStatus.UNAUTHORIZED);
                    });
        }

        return gatewayJwtVerifier.verifyAccessToken(token)
                .flatMap(principal -> continueHandshake(exchange, chain, traceId, clientIp, principal))
                .onErrorResume(IllegalStateException.class, exception -> {
                    withTrace(traceId, () -> log.warn("gateway.websocket.handshake.rejected path={} reason=invalid_access_token clientIp={}",
                            exchange.getRequest().getPath().value(),
                            clientIp));
                    return reject(exchange, HttpStatus.UNAUTHORIZED);
                });
    }

    private Mono<Void> continueAdminHandshake(ServerWebExchange exchange,
                                              WebFilterChain chain,
                                              String traceId,
                                              String clientIp,
                                              String adminUserId) {
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set("X-Admin-User-Id", adminUserId);
                    headers.set(TraceIdConstants.TRACE_ID_HEADER, traceId);
                    headers.set(securityProperties.getClientIpHeader(), clientIp);
                })
                .build();
        withTrace(traceId, () -> log.info("gateway.websocket.handshake.accepted path={} adminUserId={} clientIp={}",
                exchange.getRequest().getPath().value(),
                adminUserId,
                clientIp));
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private Mono<Void> continueHandshake(ServerWebExchange exchange,
                                         WebFilterChain chain,
                                         String traceId,
                                         String clientIp,
                                         GatewayAuthenticatedPrincipal principal) {
        if ("BANNED".equals(principal.status())) {
            withTrace(traceId, () -> log.warn("gateway.websocket.handshake.rejected path={} userId={} reason=user_banned clientIp={}",
                    exchange.getRequest().getPath().value(),
                    principal.userId(),
                    clientIp));
            return reject(exchange, HttpStatus.FORBIDDEN);
        }

        if (!sessionRegistry.tryAcquire(principal.userId(), securityProperties.getMarketWebSocketConnectionLimit())) {
            withTrace(traceId, () -> log.warn("gateway.websocket.handshake.rejected path={} userId={} reason=connection_limit_exceeded limit={} clientIp={}",
                    exchange.getRequest().getPath().value(),
                    principal.userId(),
                    securityProperties.getMarketWebSocketConnectionLimit(),
                    clientIp));
            return reject(exchange, HttpStatus.TOO_MANY_REQUESTS);
        }

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set("X-User-Id", principal.userId());
                    headers.set("X-User-Uid", principal.uid());
                    headers.set("X-User-Status", principal.status());
                    headers.set("X-User-Group-Code", principal.groupCode());
                    headers.set("X-User-Jti", principal.jti());
                    headers.set(TraceIdConstants.TRACE_ID_HEADER, traceId);
                    headers.set(securityProperties.getClientIpHeader(), clientIp);
                })
                .build();
        withTrace(traceId, () -> log.info("gateway.websocket.handshake.accepted path={} userId={} status={} clientIp={}",
                exchange.getRequest().getPath().value(),
                principal.userId(),
                principal.status(),
                clientIp));
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private boolean isTradingWebSocketHandshake(ServerHttpRequest request) {
        return TRADING_WS_PATH.equals(request.getPath().value())
                && "websocket".equalsIgnoreCase(request.getHeaders().getUpgrade());
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    private String resolveClientIp(ServerHttpRequest request) {
        String clientIpHeader = request.getHeaders().getFirst(securityProperties.getClientIpHeader());
        if (clientIpHeader != null && !clientIpHeader.isBlank()) {
            return clientIpHeader.trim();
        }
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        if (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
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
