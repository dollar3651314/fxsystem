package com.falconx.wallet.security;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.wallet.config.WalletServiceProperties;
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
 * STAGE-2-DEPOSIT R4：wallet-service internal RPC token 校验 filter。
 *
 * <p>拦截 {@code /internal/v1/wallet/**}：
 * <ol>
 *   <li>{@code X-Internal-Token} 缺失 → 90702 INTERNAL_TOKEN_MISSING</li>
 *   <li>token 不匹配 → 90701 INTERNAL_TOKEN_INVALID</li>
 *   <li>{@code X-Admin-User-Id} 缺失 → 90703 INTERNAL_ADMIN_USER_ID_MISSING</li>
 * </ol>
 *
 * <p>与 trading-core {@code TradingInternalApiTokenFilter} 同构。
 */
@Component
public class WalletInternalApiTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WalletInternalApiTokenFilter.class);
    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";
    private static final String HEADER_ADMIN_USER_ID = "X-Admin-User-Id";

    private final WalletServiceProperties properties;
    private final ObjectMapper objectMapper;

    public WalletInternalApiTokenFilter(WalletServiceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/internal/v1/wallet/");
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
            log.warn("wallet.internal.token.invalid path={}", request.getRequestURI());
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
        } catch (JacksonException e) {
            response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
        }
    }
}
