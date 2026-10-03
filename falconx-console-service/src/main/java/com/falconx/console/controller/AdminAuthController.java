package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminAuthTokenResponse;
import com.falconx.console.api.AdminChangePasswordRequest;
import com.falconx.console.api.AdminLoginRequest;
import com.falconx.console.api.AdminRefreshRequest;
import com.falconx.console.application.AdminAuthApplicationService;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.security.AdminPrincipal;
import com.falconx.console.security.AdminSecurityContextHolder;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端鉴权控制器。
 *
 * <p>4 个端点对齐 [`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §2：
 *
 * <ul>
 *   <li>POST {@code /admin/auth/login}</li>
 *   <li>POST {@code /admin/auth/refresh}</li>
 *   <li>POST {@code /admin/auth/logout}（需要 access token）</li>
 *   <li>POST {@code /admin/auth/change-password}（需要 access token）</li>
 * </ul>
 *
 * <p>{@code login} / {@code refresh} 是公开端点（{@link com.falconx.console.security.AdminAuthenticationFilter#PUBLIC_ENDPOINTS}）；
 * {@code logout} / {@code change-password} 由鉴权 filter 注入 {@link AdminPrincipal}。
 */
@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthController.class);

    private final AdminAuthApplicationService adminAuthApplicationService;

    public AdminAuthController(AdminAuthApplicationService adminAuthApplicationService) {
        this.adminAuthApplicationService = adminAuthApplicationService;
    }

    /**
     * 管理员登录。
     *
     * @param request 登录请求
     * @param servletRequest servlet 请求（用于解析客户端 IP）
     * @return token 响应
     */
    @PostMapping("/login")
    public ApiResponse<AdminAuthTokenResponse> login(@Valid @RequestBody AdminLoginRequest request,
                                                     HttpServletRequest servletRequest) {
        log.info("admin.http.login.received");
        AdminAuthTokenResponse response = adminAuthApplicationService.login(
                request.username(), request.password(), resolveClientIp(servletRequest));
        return success(response);
    }

    /**
     * Refresh token 刷新。
     *
     * @param request 刷新请求
     * @return token 响应
     */
    @PostMapping("/refresh")
    public ApiResponse<AdminAuthTokenResponse> refresh(@Valid @RequestBody AdminRefreshRequest request) {
        log.info("admin.http.refresh.received");
        AdminAuthTokenResponse response = adminAuthApplicationService.refresh(request.refreshToken());
        return success(response);
    }

    /**
     * 登出。
     *
     * @return 成功响应
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        AdminPrincipal principal = requireAuthenticated();
        log.info("admin.http.logout.received userId={}", principal.adminUserId());
        adminAuthApplicationService.logout(principal);
        return success(null);
    }

    /**
     * 修改密码。
     *
     * @param request 改密请求
     * @return 成功响应
     */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody AdminChangePasswordRequest request) {
        AdminPrincipal principal = requireAuthenticated();
        log.info("admin.http.change-password.received userId={}", principal.adminUserId());
        adminAuthApplicationService.changePassword(principal, request.oldPassword(), request.newPassword());
        return success(null);
    }

    private AdminPrincipal requireAuthenticated() {
        AdminPrincipal principal = AdminSecurityContextHolder.current();
        if (principal == null) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        return principal;
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                "0",
                "success",
                data,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma == -1 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
    }
}
