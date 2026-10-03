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
import java.util.Set;
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
 * gateway 认证入口与 trading 用户维度限流过滤器。
 *
 * <p>该过滤器在 gateway 鉴权之后执行：
 *
 * <ul>
 *   <li>对公开认证接口按 IP 做每分钟限流</li>
 *   <li>对 `/api/v1/trading/**` 按已鉴权用户做每秒限流</li>
 * </ul>
 */
@Component
public class GatewayRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayRateLimitFilter.class);
    private static final Set<String> AUTH_LIMITED_PATHS = Set.of("/api/v1/auth/login", "/api/v1/auth/register");
    private static final String AUTH_RATE_LIMIT_KEY_PREFIX = "falconx:gateway:rate:auth:";
    private static final String TRADING_RATE_LIMIT_KEY_PREFIX = "falconx:gateway:rate:trading:";

    private final ReactiveStringRedisTemplate reactiveStringRedisTemplate;
    private final GatewaySecurityProperties securityProperties;
    private final ObjectMapper objectMapper;
    private final SystemConfigClient configClient;

    public GatewayRateLimitFilter(ReactiveStringRedisTemplate reactiveStringRedisTemplate,
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
        if (AUTH_LIMITED_PATHS.contains(path)) {
            return applyAuthRateLimit(exchange, chain, path);
        }
        if (path.startsWith("/api/v1/trading/")) {
            return applyTradingRateLimit(exchange, chain, path);
        }
        return chain.filter(exchange);
    }

    private Mono<Void> applyAuthRateLimit(ServerWebExchange exchange, GatewayFilterChain chain, String path) {
        String clientIp = resolveClientIp(exchange.getRequest());
        String key = rateLimitKey(path, clientIp);
        return reactiveStringRedisTemplate.opsForValue().increment(key)
                .flatMap(currentCount -> reactiveStringRedisTemplate.expire(key, Duration.ofMinutes(2))
                        .thenReturn(currentCount))
                .flatMap(currentCount -> {
                    int authLimit = configClient.getInt(
                            "gateway.ratelimit.auth-per-minute",
                            securityProperties.getAuthRequestRateLimitPerMinute());
                    if (currentCount != null && currentCount > authLimit) {
                        log.warn("gateway.auth.rate-limited path={} clientIp={} currentCount={}", path, clientIp, currentCount);
                        if ("/api/v1/auth/register".equals(path)) {
                            return writeError(exchange, HttpStatus.TOO_MANY_REQUESTS, "10004", "Register Rate Limited");
                        }
                        return writeError(exchange, HttpStatus.TOO_MANY_REQUESTS, "10003", "Login Rate Limited");
                    }
                    return chain.filter(exchange);
                });
    }

    private Mono<Void> applyTradingRateLimit(ServerWebExchange exchange, GatewayFilterChain chain, String path) {
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (userId == null || userId.isBlank()) {
            return chain.filter(exchange);
        }

        String key = TRADING_RATE_LIMIT_KEY_PREFIX + userId + ":" + currentSecondBucket();
        return reactiveStringRedisTemplate.opsForValue().increment(key)
                .flatMap(currentCount -> reactiveStringRedisTemplate.expire(key, Duration.ofSeconds(2))
                        .thenReturn(currentCount))
                .flatMap(currentCount -> {
                    int tradingLimit = configClient.getInt(
                            "gateway.ratelimit.trading-per-second",
                            securityProperties.getTradingRequestRateLimitPerSecond());
                    if (currentCount != null && currentCount > tradingLimit) {
                        log.warn("gateway.trading.rate-limited path={} userId={} currentCount={}",
                                path,
                                userId,
                                currentCount);
                        return writeError(exchange,
                                HttpStatus.TOO_MANY_REQUESTS,
                                GatewayErrorCode.TRADING_RATE_LIMITED.code(),
                                GatewayErrorCode.TRADING_RATE_LIMITED.message());
                    }
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 15;
    }

    private String rateLimitKey(String path, String clientIp) {
        long currentMinuteBucket = Instant.now().getEpochSecond() / 60;
        return AUTH_RATE_LIMIT_KEY_PREFIX + sanitizePath(path) + ":" + clientIp + ":" + currentMinuteBucket;
    }

    private long currentSecondBucket() {
        return Instant.now().getEpochSecond();
    }

    private String sanitizePath(String path) {
        return path.replace('/', ':');
    }

    private String resolveClientIp(ServerHttpRequest request) {
        // STAGE-12 安全：只有 remoteAddress 是可信代理时才信任 X-Client-Ip header。
        // 见 GatewayGlobalRateLimitFilter.resolveClientIp 同款逻辑。
        String remoteAddr = null;
        if (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null) {
            remoteAddr = request.getRemoteAddress().getAddress().getHostAddress();
        }
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

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        String traceId = exchange.getResponse().getHeaders().getFirst(TraceIdConstants.TRACE_ID_HEADER);
        exchange.getResponse().setStatusCode(status);
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
