package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminNotificationItem;
import com.falconx.console.api.AdminNotificationListResponse;
import com.falconx.console.api.AdminNotificationSendRequest;
import com.falconx.console.api.AdminNotificationTemplateItem;
import com.falconx.console.api.AdminNotificationTemplateListResponse;
import com.falconx.console.api.AdminNotificationTemplateUpsertRequest;
import com.falconx.console.notification.AdminNotificationApplicationService;
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
 * STAGE-8-NOTIFICATION Phase 3：管理端通知 + 模板 controller。
 *
 * <p>路径：
 * <ul>
 *   <li>{@code /admin/notification-templates}（5 端点，模板 CRUD）</li>
 *   <li>{@code /admin/notifications}（3 端点，list / detail / send）</li>
 * </ul>
 *
 * <p>RBAC：{@code notification:view} / {@code notification:template:view} /
 * {@code notification:template:manage}（高危）/ {@code notification:send}（高危）。
 */
@RestController
@RequestMapping("/admin")
public class AdminNotificationController {

    private static final Logger log = LoggerFactory.getLogger(AdminNotificationController.class);

    private final AdminNotificationApplicationService adminService;

    public AdminNotificationController(AdminNotificationApplicationService adminService) {
        this.adminService = adminService;
    }

    // ---------- 模板 CRUD ----------

    @GetMapping("/notification-templates")
    @RequiresPermission(value = "notification:template:view", description = "查看通知模板列表 + 详情")
    public ApiResponse<AdminNotificationTemplateListResponse> listTemplates(
            @RequestParam(required = false) Integer enabled,
            @RequestParam(required = false) Integer level,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.notification.template.list.received enabled={} level={} page={}",
                enabled, level, page);
        return success(adminService.listTemplates(enabled, level, page, size));
    }

    @GetMapping("/notification-templates/{code}")
    @RequiresPermission(value = "notification:template:view", description = "查看通知模板详情")
    public ApiResponse<AdminNotificationTemplateItem> getTemplate(@PathVariable String code) {
        return success(adminService.getTemplate(code));
    }

    @PostMapping("/notification-templates")
    @RequiresPermission(value = "notification:template:manage", description = "新建通知模板（高危）")
    public ApiResponse<AdminNotificationTemplateItem> createTemplate(
            @Valid @RequestBody AdminNotificationTemplateUpsertRequest req) {
        log.info("admin.http.notification.template.create.received code={}", req.code());
        return success(adminService.createTemplate(req));
    }

    @PutMapping("/notification-templates/{code}")
    @RequiresPermission(value = "notification:template:manage", description = "编辑通知模板（高危）")
    public ApiResponse<AdminNotificationTemplateItem> updateTemplate(
            @PathVariable String code,
            @Valid @RequestBody AdminNotificationTemplateUpsertRequest req) {
        log.info("admin.http.notification.template.update.received code={}", code);
        return success(adminService.updateTemplate(code, req));
    }

    @DeleteMapping("/notification-templates/{code}")
    @RequiresPermission(value = "notification:template:manage", description = "删除通知模板（高危）")
    public ApiResponse<Void> deleteTemplate(@PathVariable String code) {
        log.info("admin.http.notification.template.delete.received code={}", code);
        adminService.deleteTemplate(code);
        return success(null);
    }

    // ---------- 通知 list / detail / send ----------

    @GetMapping("/notifications")
    @RequiresPermission(value = "notification:view", description = "查看用户通知列表（运营）")
    public ApiResponse<AdminNotificationListResponse> listNotifications(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String templateCode,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String fromCreatedAt,
            @RequestParam(required = false) String toCreatedAt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.notification.list.received userId={} type={} templateCode={}",
                userId, type, templateCode);
        return success(adminService.listNotifications(
                userId, type, templateCode, level, status, fromCreatedAt, toCreatedAt, page, size));
    }

    @GetMapping("/notifications/{id}")
    @RequiresPermission(value = "notification:view", description = "查看用户通知详情（运营）")
    public ApiResponse<AdminNotificationItem> getNotification(@PathVariable String id) {
        return success(adminService.getNotification(id));
    }

    @PostMapping("/notifications/send")
    @RequiresPermission(value = "notification:send", description = "手动发送通知（高危）")
    public ApiResponse<AdminNotificationItem> sendManual(
            @Valid @RequestBody AdminNotificationSendRequest req) {
        log.info("admin.http.notification.send.received userId={} templateCode={} reason={}",
                req.userId(), req.templateCode(), req.reason());
        return success(adminService.sendManual(req));
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
