package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.application.TradingNotificationApplicationService;
import com.falconx.trading.entity.TradingNotification;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-8-NOTIFICATION：用户站内信 REST。
 * 路径：{@code /api/v1/trading/notifications}
 */
@RestController
@RequestMapping("/api/v1/trading/notifications")
public class TradingNotificationController {

    private final TradingNotificationApplicationService notificationService;

    public TradingNotificationController(TradingNotificationApplicationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ApiResponse<NotificationListResponse> list(
            @RequestHeader("X-User-Id") long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<TradingNotification> items = notificationService.list(userId, page, size);
        long total = notificationService.count(userId);
        int unread = notificationService.countUnread(userId);
        List<NotificationItemResponse> mapped = items.stream().map(TradingNotificationController::toItem).toList();
        return success(new NotificationListResponse(Math.max(1, page), Math.min(Math.max(1, size), 100), total, unread, mapped));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount(@RequestHeader("X-User-Id") long userId) {
        return success(Map.of("unread", notificationService.countUnread(userId)));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Map<String, Object>> markRead(@RequestHeader("X-User-Id") long userId,
                                                     @PathVariable long id) {
        boolean updated = notificationService.markRead(userId, id);
        return success(Map.of("updated", updated, "unread", notificationService.countUnread(userId)));
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Object>> markAllRead(@RequestHeader("X-User-Id") long userId) {
        int updated = notificationService.markAllRead(userId);
        return success(Map.of("updated", updated, "unread", notificationService.countUnread(userId)));
    }

    private static NotificationItemResponse toItem(TradingNotification n) {
        return new NotificationItemResponse(
                String.valueOf(n.id()),
                n.type(),
                n.level().name(),
                n.title(),
                n.body(),
                n.relatedKey(),
                n.relatedId() == null ? null : String.valueOf(n.relatedId()),
                n.payloadJson(),
                n.status().name(),
                n.readAt(),
                n.createdAt()
        );
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }

    public record NotificationListResponse(int page, int pageSize, long total, int unread,
                                            List<NotificationItemResponse> items) {}

    public record NotificationItemResponse(
            String id,
            String type,
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
}
