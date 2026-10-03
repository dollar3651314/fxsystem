package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminAuditLogItem;
import com.falconx.console.api.AdminAuditLogListResponse;
import com.falconx.console.audit.AdminAuditLogApplicationService;
import com.falconx.console.security.RequiresPermission;
import com.falconx.infrastructure.trace.TraceIdConstants;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-9-RISK-OPS-COMPLETE §12.2：管理端审计日志查询 controller。
 *
 * <p>路径：{@code /admin/audit-logs}。
 *
 * <p>RBAC：{@code audit-log:view}（非高危，仅 read；写入由 OperationAuditAspect 自动追加）。
 */
@RestController
@RequestMapping("/admin/audit-logs")
public class AdminAuditLogController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditLogController.class);

    private final AdminAuditLogApplicationService adminService;

    public AdminAuditLogController(AdminAuditLogApplicationService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    @RequiresPermission(value = "audit-log:view", description = "查看审计日志列表")
    public ApiResponse<AdminAuditLogListResponse> list(
            @RequestParam(required = false) Long adminUserId,
            @RequestParam(required = false) String permissionCode,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) OffsetDateTime fromOccurredAt,
            @RequestParam(required = false) OffsetDateTime toOccurredAt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.audit-log.list.received adminUserId={} permissionCode={} riskLevel={} page={}",
                adminUserId, permissionCode, riskLevel, page);
        return success(adminService.list(adminUserId, permissionCode, targetType, targetId,
                riskLevel, fromOccurredAt, toOccurredAt, page, size));
    }

    @GetMapping("/{id}")
    @RequiresPermission(value = "audit-log:view", description = "查看审计日志详情")
    public ApiResponse<AdminAuditLogItem> detail(@PathVariable long id) {
        return success(adminService.detail(id));
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
