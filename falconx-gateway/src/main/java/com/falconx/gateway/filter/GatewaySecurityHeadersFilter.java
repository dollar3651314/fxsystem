package com.falconx.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * gateway Security Headers 全局过滤器。
 *
 * <p>STAGE-12 安全审计加固：在所有响应上统一注入业界标准防御 Header。
 *
 * <ul>
 *   <li>X-Content-Type-Options: nosniff — 阻止 MIME 嗅探攻击</li>
 *   <li>X-Frame-Options: DENY — 阻止 clickjacking（iframe 嵌入）</li>
 *   <li>Referrer-Policy: strict-origin-when-cross-origin — 跨站不泄露完整 URL</li>
 *   <li>Permissions-Policy: 关闭不需要的浏览器 API（camera / mic 等）</li>
 * </ul>
 *
 * <p>注意：CSP / HSTS 故意不在此层加，由 nginx-edge 处理（CSP 需精细配置 vite dev / 生产；
 * HSTS 强依赖 HTTPS，dev 环境会导致 localhost 锁死 https）。
 */
@Component
public class GatewaySecurityHeadersFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // afterCommit 注册写 header 回调 — 在响应即将发送前注入
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            // 仅当 header 不存在时设置，防止覆盖下游/nginx 已设的同名 header
            putIfAbsent(headers, "X-Content-Type-Options", "nosniff");
            putIfAbsent(headers, "X-Frame-Options", "DENY");
            putIfAbsent(headers, "Referrer-Policy", "strict-origin-when-cross-origin");
            putIfAbsent(headers, "Permissions-Policy",
                    "camera=(), microphone=(), geolocation=(), payment=()");
        }));
    }

    private static void putIfAbsent(HttpHeaders headers, String name, String value) {
        if (headers.getFirst(name) == null) {
            headers.add(name, value);
        }
    }

    @Override
    public int getOrder() {
        // 在所有业务 filter 之后执行，确保 header 不被下游覆盖；同时早于响应 commit。
        return Ordered.LOWEST_PRECEDENCE - 100;
    }
}
