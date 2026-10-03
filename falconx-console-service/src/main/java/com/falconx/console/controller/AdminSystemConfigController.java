package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminSystemConfigAuditResponse;
import com.falconx.console.api.AdminSystemConfigListResponse;
import com.falconx.console.api.AdminSystemConfigUpdateRequest;
import com.falconx.console.entity.SystemConfigCategory;
import com.falconx.console.entity.SystemConfigEntry;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.security.AdminPrincipal;
import com.falconx.console.security.AdminSecurityContextHolder;
import com.falconx.console.security.RequiresPermission;
import com.falconx.console.systemconfig.SystemConfigApplicationService;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-13 系统配置中心 controller。
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET    {@code /admin/system-config} - 列表（可按 category 过滤）</li>
 *   <li>GET    {@code /admin/system-config/{key}} - 单条详情</li>
 *   <li>PUT    {@code /admin/system-config/{key}} - 更新值</li>
 *   <li>POST   {@code /admin/system-config/{key}/reset} - 重置为默认值</li>
 *   <li>GET    {@code /admin/system-config/{key}/history} - 变更审计</li>
 * </ul>
 *
 * <p>权限：所有写操作要求 {@code system-config:write}；读操作要求 {@code system-config:view}。
 * SUPER_ADMIN 自动放行（同其他 admin controller 一致）。
 */
@RestController
@RequestMapping("/admin/system-config")
public class AdminSystemConfigController {

    private static final Logger log = LoggerFactory.getLogger(AdminSystemConfigController.class);

    private final SystemConfigApplicationService applicationService;

    public AdminSystemConfigController(SystemConfigApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping
    @RequiresPermission(value = "system-config:view", description = "查看系统配置")
    public ApiResponse<AdminSystemConfigListResponse> list(
            @RequestParam(required = false) String category) {
        AdminPrincipal principal = requireAuthenticated();
        log.info("admin.http.system-config.list.received operator={} category={}",
                principal.adminUserId(), category);
        var entries = category == null || category.isBlank()
                ? applicationService.listAll()
                : applicationService.listByCategory(SystemConfigCategory.valueOf(category));
        var items = entries.stream()
                .map(AdminSystemConfigListResponse.Item::from)
                .toList();
        return success(new AdminSystemConfigListResponse(items));
    }

    @GetMapping("/{configKey}")
    @RequiresPermission(value = "system-config:view", description = "查看单条系统配置")
    public ApiResponse<AdminSystemConfigListResponse.Item> get(@PathVariable String configKey) {
        AdminPrincipal principal = requireAuthenticated();
        log.info("admin.http.system-config.get.received operator={} key={}",
                principal.adminUserId(), configKey);
        SystemConfigEntry entry = applicationService.getByKey(configKey);
        return success(AdminSystemConfigListResponse.Item.from(entry));
    }

    @PutMapping("/{configKey}")
    @RequiresPermission(value = "system-config:write", description = "修改系统配置")
    public ApiResponse<AdminSystemConfigListResponse.Item> update(
            @PathVariable String configKey,
            @Valid @RequestBody AdminSystemConfigUpdateRequest request,
            HttpServletRequest httpRequest) {
        AdminPrincipal principal = requireAuthenticated();
        log.info("admin.http.system-config.update.received operator={} key={}",
                principal.adminUserId(), configKey);
        SystemConfigEntry updated = applicationService.updateValue(
                configKey,
                request.newValue(),
                principal.adminUserId(),
                principal.username(),
                resolveClientIp(httpRequest),
                request.reason()
        );
        return success(AdminSystemConfigListResponse.Item.from(updated));
    }

    @PostMapping("/{configKey}/reset")
    @RequiresPermission(value = "system-config:write", description = "重置系统配置为默认值")
    public ApiResponse<AdminSystemConfigListResponse.Item> reset(
            @PathVariable String configKey,
            HttpServletRequest httpRequest) {
        AdminPrincipal principal = requireAuthenticated();
        log.info("admin.http.system-config.reset.received operator={} key={}",
                principal.adminUserId(), configKey);
        SystemConfigEntry reset = applicationService.resetToDefault(
                configKey,
                principal.adminUserId(),
                principal.username(),
                resolveClientIp(httpRequest)
        );
        return success(AdminSystemConfigListResponse.Item.from(reset));
    }

    @GetMapping("/{configKey}/history")
    @RequiresPermission(value = "system-config:view", description = "查看系统配置变更历史")
    public ApiResponse<AdminSystemConfigAuditResponse> history(
            @PathVariable String configKey,
            @RequestParam(defaultValue = "50") int limit) {
        AdminPrincipal principal = requireAuthenticated();
        log.info("admin.http.system-config.history.received operator={} key={} limit={}",
                principal.adminUserId(), configKey, limit);
        var items = applicationService.getAuditHistory(configKey, limit).stream()
                .map(AdminSystemConfigAuditResponse.Item::from)
                .toList();
        return success(new AdminSystemConfigAuditResponse(items));
    }

    private AdminPrincipal requireAuthenticated() {
        AdminPrincipal principal = AdminSecurityContextHolder.current();
        if (principal == null) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TOKEN_INVALID);
        }
        return principal;
    }

    private String resolveClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
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
