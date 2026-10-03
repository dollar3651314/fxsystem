package com.falconx.gateway.filter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.falconx.common.api.ApiResponse;
import com.falconx.gateway.config.GatewaySecurityProperties;
import com.falconx.gateway.error.GatewayErrorCode;
import com.falconx.infrastructure.config.SystemConfigClient;
import com.falconx.infrastructure.trace.TraceIdConstants;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * gateway 全局 IP 兜底限流过滤器。
 *
 * <p>该过滤器在鉴权之前执行，对所有 `/api/v1/**` 请求按客户端 IP 做每分钟限流，
 * 用于补齐 Stage 6B 的全局防刷兜底能力。
 */
@Component
public class GatewayGlobalRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayGlobalRateLimitFilter.class);
    private static final String GLOBAL_RATE_LIMIT_KEY_PREFIX = "falconx:gateway:rate:global:";

    private final ReactiveStringRedisTemplate reactiveStringRedisTemplate;
    private final GatewaySecurityProperties securityProperties;
    private final ObjectMapper objectMapper;
    private final SystemConfigClient configClient;

    public GatewayGlobalRateLimitFilter(ReactiveStringRedisTemplate reactiveStringRedisTemplate,
                                        GatewaySecurityProperties securityProperties,
                                        ObjectMapper objectMapper,
                                        SystemConfigClient configClient) {
        this.reactiveStringRedisTemplate = reactiveStringRedisTemplate;
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
        this.configClient = configClient;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/v1/")) {
            return chain.filter(exchange);
        }

        String clientIp = resolveClientIp(exchange.getRequest());
        String key = GLOBAL_RATE_LIMIT_KEY_PREFIX + clientIp + ":" + currentMinuteBucket();
        return reactiveStringRedisTemplate.opsForValue().increment(key)
                .flatMap(currentCount -> reactiveStringRedisTemplate.expire(key, Duration.ofMinutes(2))
                        .thenReturn(currentCount))
                .flatMap(currentCount -> {
                    // STAGE-13 动态配置：limit 从 SystemConfigClient 读取，admin 可热改
                    int limit = configClient.getInt(
                            "gateway.ratelimit.global-per-minute",
                            securityProperties.getGlobalRequestRateLimitPerMinute());
                    if (currentCount != null && currentCount > limit) {
                        log.warn("gateway.global.rate-limited path={} clientIp={} currentCount={}",
                                path,
                                clientIp,
                                currentCount);
                        return writeError(exchange, HttpStatus.TOO_MANY_REQUESTS, GatewayErrorCode.GLOBAL_IP_RATE_LIMITED);
                    }
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    private long currentMinuteBucket() {
        return Instant.now().getEpochSecond() / 60;
    }

    private String resolveClientIp(ServerHttpRequest request) {
        // STAGE-12 安全：只有当 remoteAddress 是可信代理（nginx / 本地 / docker bridge）
        // 时才信任客户端传入的 X-Client-Ip header；否则只用 remoteAddress。
        // 否则攻击者直接发请求并伪造 X-Client-Ip: 1.2.3.X 可旋转 IP 绕过限流。
        String remoteAddr = null;
        if (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null) {
            remoteAddr = request.getRemoteAddress().getAddress().getHostAddress();
        }
        // STAGE-13 动态配置：trustedProxyIps 从 SystemConfigClient 读取
        java.util.List<String> trusted = configClient.getStringList(
                "gateway.security.trusted-proxy-ips",
                securityProperties.getTrustedProxyIps());
        if (remoteAddr != null && trusted.contains(remoteAddr)) {
            String clientIpHeader = request.getHeaders().getFirst(securityProperties.getClientIpHeader());
            if (clientIpHeader != null && !clientIpHeader.isBlank()) {
                return clientIpHeader.trim();
            }
        }
        return remoteAddr != null ? remoteAddr : "unknown";
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, GatewayErrorCode errorCode) {
        String traceId = exchange.getResponse().getHeaders().getFirst(TraceIdConstants.TRACE_ID_HEADER);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        ApiResponse<Void> responseBody = new ApiResponse<>(
                errorCode.code(),
                errorCode.message(),
                null,
                OffsetDateTime.now(),
                traceId
        );
        try {
            byte[] bytes = objectMapper.writeValueAsString(responseBody).getBytes(StandardCharsets.UTF_8);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (JacksonException exception) {
            return exchange.getResponse().setComplete();
        }
    }
}
