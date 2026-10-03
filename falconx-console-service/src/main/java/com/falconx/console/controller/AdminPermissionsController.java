package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminPermissionListResponse;
import com.falconx.console.permission.AdminPermissionApplicationService;
import com.falconx.console.security.RequiresPermission;
import com.falconx.infrastructure.trace.TraceIdConstants;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 阶段 1 P5 权限点字典 controller（只读，按 [`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §5.4）。
 */
@RestController
@RequestMapping("/admin/admin-permissions")
public class AdminPermissionsController {

    private static final Logger log = LoggerFactory.getLogger(AdminPermissionsController.class);

    private final AdminPermissionApplicationService applicationService;

    public AdminPermissionsController(AdminPermissionApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping
    @RequiresPermission(value = "admin-permission:view", description = "查看权限点字典")
    public ApiResponse<AdminPermissionListResponse> list(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(name = "highRiskOnly", defaultValue = "false") boolean highRiskOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.permissions.list.received module={} action={} highRiskOnly={} page={} size={}",
                module, action, highRiskOnly, page, size);
        AdminPermissionListResponse response = applicationService.listPermissions(
                module, action, highRiskOnly, page, size);
        return success(response);
    }

    @GetMapping("/{code}/roles")
    @RequiresPermission(value = "admin-permission:view", description = "查看权限点关联角色")
    public ApiResponse<List<AdminPermissionListResponse.RoleItem>> roles(@PathVariable String code) {
        log.info("admin.http.permissions.roles.received code={}", code);
        return success(applicationService.getRolesByPermissionCode(code));
    }

    private static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
