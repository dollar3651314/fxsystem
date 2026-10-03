package com.falconx.market.config;

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
 * market-service trace 入口过滤器。
 *
 * <p>该过滤器用于继续沿用 gateway 透传的 `traceId`，
 * 并在本地直接访问 market-service 时兜底生成新的 traceId。
 */
@Component
public class MarketTraceContextFilter extends OncePerRequestFilter {

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
