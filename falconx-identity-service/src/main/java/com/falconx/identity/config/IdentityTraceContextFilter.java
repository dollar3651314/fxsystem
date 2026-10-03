package com.falconx.identity.config;

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
 * identity-service traceId 入口过滤器。
 *
 * <p>Stage 4 之后，identity-service 既可能直接被本地测试访问，也可能经 gateway 转发访问。
 * 因此该过滤器的规则固定为：
 *
 * <ul>
 *   <li>若 gateway 已传入合法 `X-Trace-Id`，则继续沿用</li>
 *   <li>若请求未带合法 traceId，则在服务入口本地生成</li>
 * </ul>
 */
@Component
public class IdentityTraceContextFilter extends OncePerRequestFilter {

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
