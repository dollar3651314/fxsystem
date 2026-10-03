package com.falconx.gateway.filter;

import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
import com.falconx.gateway.config.GatewaySecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * gateway Trace 上下文过滤器。
 *
 * <p>该过滤器是 FalconX 全部北向流量的 traceId 起点：
 *
 * <ul>
 *   <li>忽略外部传入的 `X-Trace-Id`</li>
 *   <li>为每次外部请求生成新的系统级 traceId</li>
 *   <li>把 traceId 写入响应头和下游透传头</li>
 * </ul>
 */
@Component
public class GatewayTraceContextFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayTraceContextFilter.class);
    private final GatewaySecurityProperties securityProperties;

    public GatewayTraceContextFilter(GatewaySecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = TraceIdSupport.newTraceId();
        String clientIp = resolveClientIp(exchange);
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(TraceIdConstants.TRACE_ID_HEADER, traceId);
                    headers.set(securityProperties.getClientIpHeader(), clientIp);
                })
                .build();
        exchange.getResponse().getHeaders().set(TraceIdConstants.TRACE_ID_HEADER, traceId);
        exchange.getAttributes().put(TraceIdConstants.TRACE_ID_MDC_KEY, traceId);
        withTrace(traceId, () -> log.info("gateway.request.received path={} method={} clientIp={}",
                exchange.getRequest().getPath().value(),
                exchange.getRequest().getMethod(),
                clientIp));
        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .doOnSuccess(unused -> withTrace(traceId, () -> log.info("gateway.request.completed path={} status={}",
                        exchange.getRequest().getPath().value(),
                        exchange.getResponse().getStatusCode())))
                .doOnError(exception -> withTrace(traceId, () -> log.error("gateway.request.failed path={}",
                        exchange.getRequest().getPath().value(),
                        exception)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        if (exchange.getRequest().getRemoteAddress() != null
                && exchange.getRequest().getRemoteAddress().getAddress() != null) {
            return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
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
