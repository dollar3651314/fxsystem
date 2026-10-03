package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.application.AdminTradingNotificationApplicationService;
import com.falconx.trading.entity.NotificationChannel;
import com.falconx.trading.entity.TradingNotification;
import com.falconx.trading.entity.TradingNotificationLevel;
import com.falconx.trading.entity.TradingNotificationStatus;
import com.falconx.trading.entity.TradingNotificationTemplate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-8-NOTIFICATION Phase 3：admin 通知 + 模板 internal RPC。
 *
 * <p>路径前缀：
 * <ul>
 *   <li>{@code /internal/v1/trading/console/notification-templates}（5 端点，模板 CRUD）</li>
 *   <li>{@code /internal/v1/trading/console/notifications}（3 端点，list / detail / send）</li>
 * </ul>
 *
 * <p>鉴权：{@link com.falconx.trading.security.TradingInternalApiTokenFilter}（X-Internal-Token + X-Admin-User-Id）。
 */
@RestController
@RequestMapping("/internal/v1/trading/console")
public class AdminInternalTradingNotificationController {

    private static final Logger log = LoggerFactory.getLogger(AdminInternalTradingNotificationController.class);

    private final AdminTradingNotificationApplicationService adminService;

    public AdminInternalTradingNotificationController(AdminTradingNotificationApplicationService adminService) {
        this.adminService = adminService;
    }

    // ---------- 模板 CRUD ----------

    @GetMapping("/notification-templates")
    public ApiResponse<TemplateListResponse> listTemplates(
            @RequestParam(required = false) Integer enabled,
            @RequestParam(required = false) Integer level,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader("X-Admin-User-Id") long adminUserId) {
        log.info("trading.admin.notification.template.list.received enabled={} level={} page={} adminUserId={}",
                enabled, level, page, adminUserId);
        Boolean enabledBool = enabled == null ? null : enabled == 1;
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        List<TemplateResponse> items = adminService.listTemplates(enabledBool, level, safePage, safeSize)
                .stream().map(AdminInternalTradingNotificationController::toTemplate).toList();
        long total = adminService.countTemplates(enabledBool, level);
        return success(new TemplateListResponse(safePage, safeSize, total, items));
    }

    @GetMapping("/notification-templates/{code}")
    public ApiResponse<TemplateResponse> getTemplate(@PathVariable String code) {
        return success(toTemplate(adminService.getTemplate(code)));
    }

    @PostMapping("/notification-templates")
    public ApiResponse<TemplateResponse> createTemplate(@RequestBody TemplateUpsertRequest req,
                                                         @RequestHeader("X-Admin-User-Id") long adminUserId) {
        log.info("trading.admin.notification.template.create.received code={} adminUserId={}",
                req.code(), adminUserId);
        TradingNotificationTemplate created = adminService.createTemplate(
                req.code(), req.titleTemplate(), req.bodyTemplate(),
                parseLevel(req.level()), parseChannels(req.channels()),
                req.description(), req.enabled() == null ? true : req.enabled());
        return success(toTemplate(created));
    }

    @PutMapping("/notification-templates/{code}")
    public ApiResponse<TemplateResponse> updateTemplate(@PathVariable String code,
                                                         @RequestBody TemplateUpsertRequest req,
                                                         @RequestHeader("X-Admin-User-Id") long adminUserId) {
        log.info("trading.admin.notification.template.update.received code={} adminUserId={}", code, adminUserId);
        TradingNotificationTemplate updated = adminService.updateTemplate(
                code, req.titleTemplate(), req.bodyTemplate(),
                parseLevel(req.level()), parseChannels(req.channels()),
                req.description(), req.enabled() == null ? true : req.enabled());
        return success(toTemplate(updated));
    }

    @DeleteMapping("/notification-templates/{code}")
    public ApiResponse<Void> deleteTemplate(@PathVariable String code,
                                             @RequestHeader("X-Admin-User-Id") long adminUserId) {
        log.info("trading.admin.notification.template.delete.received code={} adminUserId={}", code, adminUserId);
        adminService.softDeleteTemplate(code);
        return success(null);
    }

    // ---------- 通知 list / detail / send ----------

    @GetMapping("/notifications")
    public ApiResponse<NotificationListResponse> listNotifications(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String templateCode,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) OffsetDateTime fromCreatedAt,
            @RequestParam(required = false) OffsetDateTime toCreatedAt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader("X-Admin-User-Id") long adminUserId) {
        log.info("trading.admin.notification.list.received userId={} type={} templateCode={} adminUserId={}",
                userId, type, templateCode, adminUserId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        List<NotificationResponse> items = adminService.listNotifications(
                userId, type, templateCode, level, status, fromCreatedAt, toCreatedAt, safePage, safeSize)
                .stream().map(AdminInternalTradingNotificationController::toNotification).toList();
        long total = adminService.countNotifications(userId, type, templateCode, level, status,
                fromCreatedAt, toCreatedAt);
        return success(new NotificationListResponse(safePage, safeSize, total, items));
    }

    @GetMapping("/notifications/{id}")
    public ApiResponse<NotificationResponse> getNotification(@PathVariable long id) {
        return success(toNotification(adminService.getNotification(id)));
    }

    @PostMapping("/notifications/send")
    public ApiResponse<NotificationResponse> sendManual(@RequestBody SendNotificationRequest req,
                                                         @RequestHeader("X-Admin-User-Id") long adminUserId) {
        log.info("trading.admin.notification.send.received userId={} templateCode={} adminUserId={} reason={}",
                req.userId(), req.templateCode(), adminUserId, req.reason());
        TradingNotification sent = adminService.sendManual(
                req.userId(), req.templateCode(),
                req.params() == null ? Map.of() : req.params(),
                req.reason());
        return success(toNotification(sent));
    }

    // ---------- DTO 映射 ----------

    private static TemplateResponse toTemplate(TradingNotificationTemplate t) {
        return new TemplateResponse(
                t.code(),
                t.titleTemplate(),
                t.bodyTemplate(),
                t.level().name(),
                t.channels().stream().map(NotificationChannel::name).toList(),
                t.description(),
                t.enabled(),
                t.createdAt(),
                t.updatedAt()
        );
    }

    private static NotificationResponse toNotification(TradingNotification n) {
        return new NotificationResponse(
                String.valueOf(n.id()),
                String.valueOf(n.userId()),
                n.type(),
                n.templateCode(),
                n.level() == null ? "INFO" : n.level().name(),
                n.title(),
                n.body(),
                n.relatedKey(),
                n.relatedId() == null ? null : String.valueOf(n.relatedId()),
                n.payloadJson(),
                n.status() == null ? "UNREAD" : (n.status() == TradingNotificationStatus.READ ? "READ" : "UNREAD"),
                n.readAt(),
                n.createdAt()
        );
    }

    private static TradingNotificationLevel parseLevel(String level) {
        if (level == null) return TradingNotificationLevel.INFO;
        try {
            return TradingNotificationLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return TradingNotificationLevel.INFO;
        }
    }

    private static List<NotificationChannel> parseChannels(List<String> channels) {
        if (channels == null || channels.isEmpty()) return List.of(NotificationChannel.IN_APP);
        List<NotificationChannel> result = new ArrayList<>();
        for (String c : channels) {
            NotificationChannel parsed = NotificationChannel.fromName(c);
            if (parsed != null) result.add(parsed);
        }
        return result.isEmpty() ? List.of(NotificationChannel.IN_APP) : result;
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }

    // ---------- Request / Response records ----------

    public record TemplateUpsertRequest(
            String code,
            String titleTemplate,
            String bodyTemplate,
            String level,
            List<String> channels,
            String description,
            Boolean enabled
    ) {}

    public record SendNotificationRequest(
            Long userId,
            String templateCode,
            Map<String, String> params,
            String reason
    ) {}

    public record TemplateResponse(
            String code,
            String titleTemplate,
            String bodyTemplate,
            String level,
            List<String> channels,
            String description,
            boolean enabled,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    public record TemplateListResponse(int page, int pageSize, long total, List<TemplateResponse> items) {}

    public record NotificationResponse(
            String id,
            String userId,
            String type,
            String templateCode,
            String level,
            String title,
            String body,
            String relatedKey,
            String relatedId,
            String payloadJson,
            String status,
            OffsetDateTime readAt,
            OffsetDateTime createdAt
    ) {}

    public record NotificationListResponse(int page, int pageSize, long total, List<NotificationResponse> items) {}
}
