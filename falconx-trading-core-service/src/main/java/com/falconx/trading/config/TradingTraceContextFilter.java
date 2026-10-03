package com.falconx.trading.config;

import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * trading-core-service trace 入口过滤器。
 *
 * <p>该过滤器负责继续沿用 gateway 透传的 `X-Trace-Id`，
 * 并在本地直接调试 trading-core-service 时兜底生成新的 traceId。
 * 这样可以保证：
 *
 * <ul>
 *   <li>通过 gateway 进入的链路具备统一 traceId</li>
 *   <li>直接访问服务调试时也不会缺少 traceId</li>
 *   <li>日志打印规范要求的 MDC 字段始终存在</li>
 * </ul>
 */
@Component
public class TradingTraceContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceIdSupport.reuseOrCreate(request.getHeader(TraceIdConstants.TRACE_ID_HEADER));
        MDC.put(TraceIdConstants.TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TraceIdConstants.TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIdConstants.TRACE_ID_MDC_KEY);
        }
    }
}
