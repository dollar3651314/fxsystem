package com.falconx.console.notification;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminNotificationItem;
import com.falconx.console.api.AdminNotificationListResponse;
import com.falconx.console.api.AdminNotificationSendRequest;
import com.falconx.console.api.AdminNotificationTemplateItem;
import com.falconx.console.api.AdminNotificationTemplateListResponse;
import com.falconx.console.api.AdminNotificationTemplateUpsertRequest;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.AdminUserInfoEnricher;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * STAGE-8-NOTIFICATION Phase 3：管理端通知 + 模板编排。
 *
 * <p>转发到 trading-core {@code /internal/v1/trading/console/notification-templates*} 与
 * {@code /internal/v1/trading/console/notifications*}；6 个 trading 错误码翻译为 console
 * 8 个 AdminErrorCode（90880-90887）。
 *
 * <p>高危写操作（template:manage / send）走 OperationAuditAspect 写
 * {@code t_admin_operation_log}（HighRiskPermissionRegistry 已注册）。
 */
@Service
public class AdminNotificationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminNotificationApplicationService.class);

    private static final ParameterizedTypeReference<ApiResponse<AdminNotificationTemplateListResponse>> TEMPLATE_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminNotificationTemplateItem>> TEMPLATE_ITEM_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminNotificationListResponse>> NOTIF_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminNotificationItem>> NOTIF_ITEM_TYPE =
            new ParameterizedTypeReference<>() {};

    private final InternalRpcClient internalRpcClient;
    private final AdminUserInfoEnricher userInfoEnricher;

    public AdminNotificationApplicationService(InternalRpcClient internalRpcClient,
                                               AdminUserInfoEnricher userInfoEnricher) {
        this.internalRpcClient = internalRpcClient;
        this.userInfoEnricher = userInfoEnricher;
    }

    // ---------- 模板 ----------

    public AdminNotificationTemplateListResponse listTemplates(Integer enabled, Integer level, int page, int size) {
        StringBuilder uri = new StringBuilder("/internal/v1/trading/console/notification-templates?page=")
                .append(page).append("&size=").append(size);
        if (enabled != null) uri.append("&enabled=").append(enabled);
        if (level != null) uri.append("&level=").append(level);
        try {
            return internalRpcClient.get(uri.toString(), TEMPLATE_LIST_TYPE);
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    public AdminNotificationTemplateItem getTemplate(String code) {
        try {
            return internalRpcClient.get("/internal/v1/trading/console/notification-templates/" + code,
                    TEMPLATE_ITEM_TYPE);
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    public AdminNotificationTemplateItem createTemplate(AdminNotificationTemplateUpsertRequest req) {
        try {
            AdminNotificationTemplateItem item = internalRpcClient.post(
                    "/internal/v1/trading/console/notification-templates",
                    toUpstreamBody(req, req.code()),
                    TEMPLATE_ITEM_TYPE);
            log.info("admin.notification.template.create.completed code={}", item.code());
            return item;
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    public AdminNotificationTemplateItem updateTemplate(String code, AdminNotificationTemplateUpsertRequest req) {
        try {
            AdminNotificationTemplateItem item = internalRpcClient.put(
                    "/internal/v1/trading/console/notification-templates/" + code,
                    toUpstreamBody(req, code),
                    TEMPLATE_ITEM_TYPE);
            log.info("admin.notification.template.update.completed code={}", item.code());
            return item;
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    public void deleteTemplate(String code) {
        try {
            internalRpcClient.delete("/internal/v1/trading/console/notification-templates/" + code, VOID_TYPE);
            log.info("admin.notification.template.delete.completed code={}", code);
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    // ---------- 通知 ----------

    public AdminNotificationListResponse listNotifications(Long userId, String type, String templateCode,
                                                            Integer level, Integer status,
                                                            String fromCreatedAt, String toCreatedAt,
                                                            int page, int size) {
        StringBuilder uri = new StringBuilder("/internal/v1/trading/console/notifications?page=")
                .append(page).append("&size=").append(size);
        if (userId != null) uri.append("&userId=").append(userId);
        if (type != null) uri.append("&type=").append(type);
        if (templateCode != null) uri.append("&templateCode=").append(templateCode);
        if (level != null) uri.append("&level=").append(level);
        if (status != null) uri.append("&status=").append(status);
        if (fromCreatedAt != null) uri.append("&fromCreatedAt=").append(fromCreatedAt);
        if (toCreatedAt != null) uri.append("&toCreatedAt=").append(toCreatedAt);
        try {
            AdminNotificationListResponse resp = internalRpcClient.get(uri.toString(), NOTIF_LIST_TYPE);
            List<AdminNotificationItem> items = userInfoEnricher.enrich(
                    resp.items(), it -> Long.parseLong(it.userId()),
                    (it, r) -> it.withUserInfo(r.uid(), r.email(), r.fullName()));
            return new AdminNotificationListResponse(resp.page(), resp.pageSize(), resp.total(), items);
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    public AdminNotificationItem getNotification(String id) {
        try {
            return internalRpcClient.get("/internal/v1/trading/console/notifications/" + id, NOTIF_ITEM_TYPE);
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    public AdminNotificationItem sendManual(AdminNotificationSendRequest req) {
        // reason 已由 @NotBlank Bean Validation 在 controller 拦截，service 层不再二次校验
        Map<String, Object> body = new HashMap<>();
        body.put("userId", Long.parseLong(req.userId()));
        body.put("templateCode", req.templateCode());
        body.put("params", req.params() == null ? Map.of() : req.params());
        body.put("reason", req.reason());
        try {
            AdminNotificationItem item = internalRpcClient.post(
                    "/internal/v1/trading/console/notifications/send", body, NOTIF_ITEM_TYPE);
            log.info("admin.notification.send.completed userId={} templateCode={} notifId={}",
                    req.userId(), req.templateCode(), item.id());
            return item;
        } catch (InternalRpcException ex) {
            translateError(ex);
            throw ex;
        }
    }

    // ---------- 内部 ----------

    private static Map<String, Object> toUpstreamBody(AdminNotificationTemplateUpsertRequest req, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("titleTemplate", req.titleTemplate());
        body.put("bodyTemplate", req.bodyTemplate());
        body.put("level", req.level());
        body.put("channels", req.channels());
        body.put("description", req.description());
        body.put("enabled", req.enabled());
        return body;
    }

    /**
     * trading-core 30060-30065 → console AdminErrorCode 90880-90887。
     */
    private void translateError(InternalRpcException ex) {
        String code = ex.getDownstreamCode();
        if (code == null) return;
        switch (code) {
            case "30060", "30065" ->
                    throw new AdminBusinessException(AdminErrorCode.ADMIN_NOTIFICATION_TEMPLATE_NOT_FOUND);
            case "30061" ->
                    throw new AdminBusinessException(AdminErrorCode.ADMIN_NOTIFICATION_TEMPLATE_CODE_DUPLICATE);
            case "30062" ->
                    throw new AdminBusinessException(AdminErrorCode.ADMIN_NOTIFICATION_TEMPLATE_IN_USE);
            case "30063" ->
                    throw new AdminBusinessException(AdminErrorCode.ADMIN_NOTIFICATION_USER_NOT_FOUND);
            case "30064" ->
                    throw new AdminBusinessException(AdminErrorCode.ADMIN_NOTIFICATION_NOT_FOUND);
            default -> { /* 透传其他错误 */ }
        }
    }
}
