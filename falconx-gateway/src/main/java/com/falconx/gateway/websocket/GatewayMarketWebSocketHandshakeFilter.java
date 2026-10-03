package com.falconx.gateway.websocket;

import com.falconx.gateway.config.GatewaySecurityProperties;
import com.falconx.gateway.security.GatewayAuthenticatedPrincipal;
import com.falconx.gateway.security.GatewayJwtVerifier;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * `/ws/v1/market` 握手过滤器。
 *
 * <p>由于该端点不走 `/api/v1/**` 的 HTTP 过滤链，WebSocket 握手的
 * token 校验、`X-Trace-Id` 生成和连接数限制都在这里单独完成。
 */
@Component
public class GatewayMarketWebSocketHandshakeFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayMarketWebSocketHandshakeFilter.class);
    private static final String MARKET_WS_PATH = "/ws/v1/market";

    private final GatewayJwtVerifier gatewayJwtVerifier;
    private final GatewaySecurityProperties securityProperties;
    private final GatewayMarketWebSocketSessionRegistry sessionRegistry;

    public GatewayMarketWebSocketHandshakeFilter(GatewayJwtVerifier gatewayJwtVerifier,
                                                 GatewaySecurityProperties securityProperties,
                                                 GatewayMarketWebSocketSessionRegistry sessionRegistry) {
        this.gatewayJwtVerifier = gatewayJwtVerifier;
        this.securityProperties = securityProperties;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isMarketWebSocketHandshake(exchange.getRequest())) {
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

        return gatewayJwtVerifier.verifyAccessToken(token)
                .flatMap(principal -> continueHandshake(exchange, chain, traceId, clientIp, principal))
                .onErrorResume(IllegalStateException.class, exception -> {
                    withTrace(traceId, () -> log.warn("gateway.websocket.handshake.rejected path={} reason=invalid_access_token clientIp={}",
                            exchange.getRequest().getPath().value(),
                            clientIp));
                    return reject(exchange, HttpStatus.UNAUTHORIZED);
                });
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

    private boolean isMarketWebSocketHandshake(ServerHttpRequest request) {
        return MARKET_WS_PATH.equals(request.getPath().value())
                && "websocket".equalsIgnoreCase(request.getHeaders().getUpgrade());
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    private String resolveClientIp(ServerHttpRequest request) {
        // STAGE-12 安全：只有 remoteAddress 是可信代理时才信任 X-Client-Ip / X-Forwarded-For。
        // 见 GatewayGlobalRateLimitFilter.resolveClientIp 同款逻辑。
        String remoteAddr = null;
        if (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null) {
            remoteAddr = request.getRemoteAddress().getAddress().getHostAddress();
        }
        if (remoteAddr != null && securityProperties.getTrustedProxyIps().contains(remoteAddr)) {
            String clientIpHeader = request.getHeaders().getFirst(securityProperties.getClientIpHeader());
            if (clientIpHeader != null && !clientIpHeader.isBlank()) {
                return clientIpHeader.trim();
            }
            String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
        }
        return remoteAddr != null ? remoteAddr : "unknown";
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
