package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminMeMenusResponse;
import com.falconx.console.api.AdminMePermissionsResponse;
import com.falconx.console.api.AdminMeResponse;
import com.falconx.console.application.AdminMeApplicationService;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.security.AdminPrincipal;
import com.falconx.console.security.AdminSecurityContextHolder;
import com.falconx.infrastructure.trace.TraceIdConstants;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前管理员相关查询控制器。
 *
 * <p>3 个端点对齐 [`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §2.5-§2.7：
 *
 * <ul>
 *   <li>GET {@code /admin/me} - 基本信息</li>
 *   <li>GET {@code /admin/me/permissions} - 权限点集合（含 isSuperAdmin 标记）</li>
 *   <li>GET {@code /admin/me/menus} - 菜单树（按权限过滤）</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/me")
public class AdminMeController {

    private static final Logger log = LoggerFactory.getLogger(AdminMeController.class);

    private final AdminMeApplicationService adminMeApplicationService;

    public AdminMeController(AdminMeApplicationService adminMeApplicationService) {
        this.adminMeApplicationService = adminMeApplicationService;
    }

    @GetMapping
    public ApiResponse<AdminMeResponse> me() {
        AdminPrincipal principal = requireAuthenticated();
        log.debug("admin.http.me.received userId={}", principal.adminUserId());
        return success(adminMeApplicationService.loadCurrentAdmin(principal));
    }

    @GetMapping("/permissions")
    public ApiResponse<AdminMePermissionsResponse> permissions() {
        AdminPrincipal principal = requireAuthenticated();
        log.debug("admin.http.me.permissions.received userId={}", principal.adminUserId());
        return success(adminMeApplicationService.loadCurrentPermissions(principal));
    }

    @GetMapping("/menus")
    public ApiResponse<AdminMeMenusResponse> menus() {
        AdminPrincipal principal = requireAuthenticated();
        log.debug("admin.http.me.menus.received userId={}", principal.adminUserId());
        return success(adminMeApplicationService.loadCurrentMenus(principal));
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
}
