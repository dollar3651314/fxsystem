package com.falconx.console.security;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 管理端 access token 鉴权 filter。
 *
 * <p>拦截所有 {@code /admin/**} 请求，按以下顺序处理：
 *
 * <ol>
 *   <li>判断是否为公开端点（{@link #PUBLIC_ENDPOINTS}），是则放行</li>
 *   <li>读取 {@code Authorization: Bearer <access>} header；缺失则返回 90003</li>
 *   <li>用 {@link AdminTokenSupport#parseAndVerifyAccessToken(String)} 校验 token</li>
 *   <li>检查 jti 是否在 {@link AdminTokenBlacklistService} 黑名单内；命中返回 90003</li>
 *   <li>将 {@link AdminPrincipal} 注入 {@link AdminSecurityContextHolder}</li>
 *   <li>放行后续 filter 链；finally 块清理 ThreadLocal</li>
 * </ol>
 *
 * <p>失败时直接构造 {@link ApiResponse} JSON 写入响应（401/403），不抛异常到 servlet 容器。
 */
public class AdminAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthenticationFilter.class);

    /** 不需要 access token 的公开端点（gateway 路由进来后由本 filter 放行）。 */
    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
            "/admin/auth/login",
            "/admin/auth/refresh"
    );

    private static final String BEARER_PREFIX = "Bearer ";

    private final AdminTokenSupport tokenSupport;
    private final AdminTokenBlacklistService blacklistService;
    private final ObjectMapper objectMapper;

    public AdminAuthenticationFilter(AdminTokenSupport tokenSupport,
                                     AdminTokenBlacklistService blacklistService,
                                     ObjectMapper objectMapper) {
        this.tokenSupport = tokenSupport;
        this.blacklistService = blacklistService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (PUBLIC_ENDPOINTS.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            AdminPrincipal principal = authenticate(request);
            AdminSecurityContextHolder.set(principal);
            log.debug("admin.auth.filter.authenticated adminUserId={} jti={}",
                    principal.adminUserId(), principal.jti());
            filterChain.doFilter(request, response);
        } catch (AdminBusinessException ex) {
            writeError(response, ex.getErrorCode());
        } finally {
            AdminSecurityContextHolder.clear();
        }
    }

    private AdminPrincipal authenticate(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        AdminPrincipal principal = tokenSupport.parseAndVerifyAccessToken(token);
        if (blacklistService.isAccessTokenBlacklisted(principal.jti())) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        return principal;
    }

    private void writeError(HttpServletResponse response, AdminErrorCode errorCode) throws IOException {
        int httpStatus = switch (errorCode) {
            case ADMIN_TOKEN_EXPIRED, ADMIN_TOKEN_INVALID, ADMIN_AUTH_FAILED -> HttpServletResponse.SC_UNAUTHORIZED;
            case ADMIN_PERMISSION_DENIED, ADMIN_IP_NOT_WHITELISTED, ADMIN_PASSWORD_MUST_CHANGE,
                 ADMIN_USER_DISABLED -> HttpServletResponse.SC_FORBIDDEN;
            case ADMIN_LOGIN_LOCKED -> 423;
            // STAGE-2-CUSTOMER 段在 filter 链路不会出现（filter 仅做鉴权 + token 校验，不抛业务码）
            // 但 switch 表达式必须穷举所有 enum 值
            default -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        };
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        String traceId = MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY);
        ApiResponse<Object> body = new ApiResponse<>(
                errorCode.code(),
                errorCode.message(),
                null,
                OffsetDateTime.now(),
                traceId
        );
        try {
            response.getWriter().write(objectMapper.writeValueAsString(body));
        } catch (JacksonException ex) {
            log.error("admin.auth.filter.serialize.failure code={} message={}", errorCode.code(), ex.getMessage());
        }
    }
}
