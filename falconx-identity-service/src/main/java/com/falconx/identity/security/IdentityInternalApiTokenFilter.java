package com.falconx.identity.security;

import com.falconx.common.api.ApiResponse;
import com.falconx.identity.config.IdentityServiceProperties;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * identity-service internal RPC token 校验 filter（STAGE-2-CUSTOMER）。
 *
 * <p>拦截所有 {@code /internal/v1/identity/**} 请求，按以下顺序处理：
 *
 * <ol>
 *   <li>检查 {@code X-Internal-Token} header；缺失返回 90702 INTERNAL_TOKEN_MISSING</li>
 *   <li>比对配置 {@code falconx.identity.internal-api.token}；不匹配返回 90701 INTERNAL_TOKEN_INVALID</li>
 *   <li>检查 {@code X-Admin-User-Id} header；缺失返回 90703 INTERNAL_ADMIN_USER_ID_MISSING</li>
 *   <li>放行后续 filter 链</li>
 * </ol>
 *
 * <p>详细机制见 {@code docs/architecture/管理端架构.md} §4.1。
 */
@Component
public class IdentityInternalApiTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdentityInternalApiTokenFilter.class);
    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";
    private static final String HEADER_ADMIN_USER_ID = "X-Admin-User-Id";

    private final IdentityServiceProperties properties;
    private final ObjectMapper objectMapper;

    public IdentityInternalApiTokenFilter(IdentityServiceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/internal/v1/identity/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = request.getHeader(HEADER_INTERNAL_TOKEN);
        if (token == null || token.isBlank()) {
            writeError(response, 401, "90702", "Internal Token Missing");
            return;
        }
        String expected = properties.getInternalApi().getToken();
        if (expected == null || expected.isBlank() || !expected.equals(token)) {
            log.warn("identity.internal.token.invalid path={}", request.getRequestURI());
            writeError(response, 401, "90701", "Internal Token Invalid");
            return;
        }
        String adminUserId = request.getHeader(HEADER_ADMIN_USER_ID);
        if (adminUserId == null || adminUserId.isBlank()) {
            writeError(response, 400, "90703", "Internal Admin User Id Missing");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int httpStatus, String code, String message) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiResponse<Object> body = new ApiResponse<>(
                code,
                message,
                null,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
        try {
            response.getWriter().write(objectMapper.writeValueAsString(body));
        } catch (JacksonException ex) {
            log.error("identity.internal.filter.serialize.failure code={} msg={}", code, ex.getMessage());
        }
    }
}
