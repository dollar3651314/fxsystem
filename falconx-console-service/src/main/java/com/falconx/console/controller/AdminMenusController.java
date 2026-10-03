package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminMenuCreateRequest;
import com.falconx.console.api.AdminMenuDetailResponse;
import com.falconx.console.api.AdminMenuListResponse;
import com.falconx.console.api.AdminMenuSortRequest;
import com.falconx.console.api.AdminMenuUpdateRequest;
import com.falconx.console.menu.AdminMenuApplicationService;
import com.falconx.console.security.RequiresPermission;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 阶段 1 P4 菜单管理 controller（[`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §5.3）。
 */
@RestController
@RequestMapping("/admin/admin-menus")
public class AdminMenusController {

    private static final Logger log = LoggerFactory.getLogger(AdminMenusController.class);

    private final AdminMenuApplicationService applicationService;

    public AdminMenusController(AdminMenuApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping
    @RequiresPermission(value = "admin-menu:view", description = "查看菜单（树/扁平）")
    public ApiResponse<AdminMenuListResponse> list(
            @RequestParam(name = "tree", defaultValue = "true") boolean tree) {
        log.info("admin.http.menus.list.received tree={}", tree);
        return success(applicationService.listMenus(tree));
    }

    @PostMapping
    @RequiresPermission(value = "admin-menu:create", description = "新建菜单")
    public ApiResponse<AdminMenuDetailResponse> create(@Valid @RequestBody AdminMenuCreateRequest request) {
        log.info("admin.http.menus.create.received code={}", request.code());
        return success(applicationService.createMenu(
                request.parentId(), request.code(), request.name(), request.icon(),
                request.path(), request.permissionCode(),
                request.sortOrder(), Boolean.TRUE.equals(request.isVisible())));
    }

    @PutMapping("/{id}")
    @RequiresPermission(value = "admin-menu:update", description = "编辑菜单")
    public ApiResponse<AdminMenuDetailResponse> update(@PathVariable long id,
                                                        @Valid @RequestBody AdminMenuUpdateRequest request) {
        log.info("admin.http.menus.update.received menuId={}", id);
        return success(applicationService.updateMenu(
                id, request.parentId(), request.name(), request.icon(), request.path(),
                request.permissionCode(), request.sortOrder(),
                Boolean.TRUE.equals(request.isVisible())));
    }

    @PatchMapping("/{id}/sort")
    @RequiresPermission(value = "admin-menu:update", description = "调整菜单排序")
    public ApiResponse<AdminMenuDetailResponse> sort(@PathVariable long id,
                                                      @Valid @RequestBody AdminMenuSortRequest request) {
        log.info("admin.http.menus.sort.received menuId={} direction={}", id, request.direction());
        return success(applicationService.moveMenu(id, request.direction()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(value = "admin-menu:delete", description = "删除菜单")
    public ApiResponse<Void> delete(@PathVariable long id) {
        log.info("admin.http.menus.delete.received menuId={}", id);
        applicationService.deleteMenu(id);
        return success(null);
    }

    private static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
