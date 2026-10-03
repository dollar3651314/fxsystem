package com.falconx.gateway.filter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.falconx.common.api.ApiResponse;
import com.falconx.gateway.security.GatewayAuthenticatedPrincipal;
import com.falconx.gateway.security.GatewayJwtVerifier;
import com.falconx.infrastructure.trace.TraceIdConstants;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * gateway 统一鉴权过滤器。
 *
 * <p>该过滤器负责 Stage 4 的最小安全闭环：
 *
 * <ul>
 *   <li>公开接口白名单放行</li>
 *   <li>校验 Access Token 结构、签名和过期时间</li>
 *   <li>根据用户状态做最小授权限制</li>
 *   <li>向下游透传 `X-User-Id / X-User-Uid / X-User-Status / X-User-Group-Code`</li>
 * </ul>
 */
@Component
public class GatewayAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthenticationFilter.class);
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh"
    );

    private final GatewayJwtVerifier gatewayJwtVerifier;
    private final ObjectMapper objectMapper;

    public GatewayAuthenticationFilter(GatewayJwtVerifier gatewayJwtVerifier, ObjectMapper objectMapper) {
        this.gatewayJwtVerifier = gatewayJwtVerifier;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/v1/") || PUBLIC_PATHS.contains(path) || HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }

        String authorizationHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            log.warn("gateway.auth.rejected path={} reason=missing_or_invalid_authorization_header", path);
            return writeError(exchange, HttpStatus.UNAUTHORIZED, "10001", "Unauthorized");
        }

        return gatewayJwtVerifier.verifyAccessToken(authorizationHeader.substring("Bearer ".length()))
                .flatMap(principal -> {
                    if ("BANNED".equals(principal.status())) {
                        log.warn("gateway.auth.rejected path={} userId={} reason=user_banned", path, principal.userId());
                        return writeError(exchange, HttpStatus.FORBIDDEN, "10002", "User Banned");
                    }
                    if ("FROZEN".equals(principal.status()) && isWriteRequest(exchange.getRequest().getMethod())) {
                        log.warn("gateway.auth.rejected path={} userId={} reason=user_frozen", path, principal.userId());
                        return writeError(exchange, HttpStatus.FORBIDDEN, "10007", "User Frozen");
                    }

                    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                            .headers(headers -> {
                                headers.set("X-User-Id", principal.userId());
                                headers.set("X-User-Uid", principal.uid());
                                headers.set("X-User-Status", principal.status());
                                headers.set("X-User-Group-Code", principal.groupCode());
                                headers.set("X-User-Jti", principal.jti());
                            })
                            .build();
                    log.info("gateway.auth.accepted path={} userId={} status={}",
                            path,
                            principal.userId(),
                            principal.status());
                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                })
                .onErrorResume(IllegalStateException.class, exception -> {
                    log.warn("gateway.auth.rejected path={} reason=invalid_access_token", path);
                    return writeError(exchange, HttpStatus.UNAUTHORIZED, "10001", "Unauthorized");
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private boolean isWriteRequest(HttpMethod method) {
        return HttpMethod.POST.equals(method)
                || HttpMethod.PUT.equals(method)
                || HttpMethod.PATCH.equals(method)
                || HttpMethod.DELETE.equals(method);
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus httpStatus, String code, String message) {
        String traceId = exchange.getResponse().getHeaders().getFirst(TraceIdConstants.TRACE_ID_HEADER);
        exchange.getResponse().setStatusCode(httpStatus);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        ApiResponse<Void> responseBody = new ApiResponse<>(code, message, null, OffsetDateTime.now(), traceId);
        try {
            byte[] bytes = objectMapper.writeValueAsString(responseBody).getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (JacksonException exception) {
            return exchange.getResponse().setComplete();
        }
    }
}
