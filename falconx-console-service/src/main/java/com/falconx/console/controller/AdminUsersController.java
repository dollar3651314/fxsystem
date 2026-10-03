package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminUserCreateRequest;
import com.falconx.console.api.AdminUserCreateResponse;
import com.falconx.console.api.AdminUserDisableRequest;
import com.falconx.console.api.AdminUserListResponse;
import com.falconx.console.api.AdminUserResetPasswordRequest;
import com.falconx.console.api.AdminUserResetPasswordResponse;
import com.falconx.console.api.AdminUserUpdateRequest;
import com.falconx.console.security.RequiresPermission;
import com.falconx.console.user.AdminUserApplicationService;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 阶段 1 P2 管理员管理 controller（[`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §5.1）。
 */
@RestController
@RequestMapping("/admin/admin-users")
public class AdminUsersController {

    private static final Logger log = LoggerFactory.getLogger(AdminUsersController.class);

    private final AdminUserApplicationService applicationService;

    public AdminUsersController(AdminUserApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping
    @RequiresPermission(value = "admin-user:view", description = "查看管理员列表")
    public ApiResponse<AdminUserListResponse> list(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String realName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.users.list.received page={} size={}", page, size);
        return success(applicationService.listUsers(username, realName, status, roleCode, from, to, page, size));
    }

    @GetMapping("/{id}")
    @RequiresPermission(value = "admin-user:view", description = "查看管理员详情")
    public ApiResponse<AdminUserListResponse.Item> detail(@PathVariable long id) {
        log.info("admin.http.users.detail.received userId={}", id);
        return success(applicationService.getUserDetail(id));
    }

    @PostMapping
    @RequiresPermission(value = "admin-user:create", description = "新建管理员")
    public ApiResponse<AdminUserCreateResponse> create(@Valid @RequestBody AdminUserCreateRequest request) {
        log.info("admin.http.users.create.received username={}", request.username());
        return success(applicationService.createUser(
                request.username(), request.realName(), request.roleIds(),
                request.password(), request.mustChangePassword()));
    }

    @PutMapping("/{id}")
    @RequiresPermission(value = "admin-user:update", description = "编辑管理员")
    public ApiResponse<AdminUserListResponse.Item> update(@PathVariable long id,
                                                           @Valid @RequestBody AdminUserUpdateRequest request) {
        log.info("admin.http.users.update.received userId={}", id);
        return success(applicationService.updateUser(id, request.realName(), request.roleIds()));
    }

    @PostMapping("/{id}/disable")
    @RequiresPermission(value = "admin-user:disable", description = "禁用管理员（高风险）")
    public ApiResponse<Void> disable(@PathVariable long id,
                                       @Valid @RequestBody AdminUserDisableRequest request) {
        log.info("admin.http.users.disable.received userId={}", id);
        applicationService.disableUser(id);
        return success(null);
    }

    @PostMapping("/{id}/enable")
    @RequiresPermission(value = "admin-user:disable", description = "启用管理员")
    public ApiResponse<Void> enable(@PathVariable long id) {
        log.info("admin.http.users.enable.received userId={}", id);
        applicationService.enableUser(id);
        return success(null);
    }

    @PostMapping("/{id}/reset-password")
    @RequiresPermission(value = "admin-user:reset-password", description = "重置管理员密码（高风险）")
    public ApiResponse<AdminUserResetPasswordResponse> resetPassword(
            @PathVariable long id,
            @Valid @RequestBody AdminUserResetPasswordRequest request) {
        log.info("admin.http.users.reset-password.received userId={}", id);
        return success(applicationService.resetPassword(id, request.password(), request.forceChangePassword()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(value = "admin-user:delete", description = "删除管理员（高风险）")
    public ApiResponse<Void> delete(@PathVariable long id) {
        log.info("admin.http.users.delete.received userId={}", id);
        applicationService.deleteUser(id);
        return success(null);
    }

    private static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
