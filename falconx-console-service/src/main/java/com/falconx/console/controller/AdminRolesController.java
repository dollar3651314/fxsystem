package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminRoleAssignPermissionsRequest;
import com.falconx.console.api.AdminRoleCreateRequest;
import com.falconx.console.api.AdminRoleDetailResponse;
import com.falconx.console.api.AdminRoleListResponse;
import com.falconx.console.api.AdminRolePermissionsResponse;
import com.falconx.console.api.AdminRoleUpdateRequest;
import com.falconx.console.role.AdminRoleApplicationService;
import com.falconx.console.security.RequiresPermission;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 阶段 1 P3 角色管理 controller（[`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §5.2）。
 */
@RestController
@RequestMapping("/admin/admin-roles")
public class AdminRolesController {

    private static final Logger log = LoggerFactory.getLogger(AdminRolesController.class);

    private final AdminRoleApplicationService applicationService;

    public AdminRolesController(AdminRoleApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping
    @RequiresPermission(value = "admin-role:view", description = "查看角色列表")
    public ApiResponse<AdminRoleListResponse> list(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Boolean isSystem,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.roles.list.received page={} size={}", page, size);
        return success(applicationService.listRoles(code, name, isSystem, page, size));
    }

    @PostMapping
    @RequiresPermission(value = "admin-role:create", description = "新建角色")
    public ApiResponse<AdminRoleDetailResponse> create(@Valid @RequestBody AdminRoleCreateRequest request) {
        log.info("admin.http.roles.create.received code={}", request.code());
        return success(applicationService.createRole(request.code(), request.name(), request.description()));
    }

    @PutMapping("/{id}")
    @RequiresPermission(value = "admin-role:update", description = "编辑角色")
    public ApiResponse<AdminRoleDetailResponse> update(@PathVariable long id,
                                                        @Valid @RequestBody AdminRoleUpdateRequest request) {
        log.info("admin.http.roles.update.received roleId={}", id);
        return success(applicationService.updateRole(id, request.name(), request.description()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(value = "admin-role:delete", description = "删除角色")
    public ApiResponse<Void> delete(@PathVariable long id) {
        log.info("admin.http.roles.delete.received roleId={}", id);
        applicationService.deleteRole(id);
        return success(null);
    }

    @GetMapping("/{id}/permissions")
    @RequiresPermission(value = "admin-role:view", description = "查看角色权限")
    public ApiResponse<AdminRolePermissionsResponse> getPermissions(@PathVariable long id) {
        log.info("admin.http.roles.permissions.get.received roleId={}", id);
        return success(applicationService.getPermissions(id));
    }

    @PutMapping("/{id}/permissions")
    @RequiresPermission(value = "admin-role:permission:assign", description = "分配角色权限（高风险）")
    public ApiResponse<AdminRolePermissionsResponse> assignPermissions(
            @PathVariable long id,
            @Valid @RequestBody AdminRoleAssignPermissionsRequest request) {
        log.info("admin.http.roles.permissions.assign.received roleId={} count={}",
                id, request.permissionCodes() == null ? 0 : request.permissionCodes().size());
        return success(applicationService.assignPermissions(id, request.permissionCodes()));
    }

    private static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
