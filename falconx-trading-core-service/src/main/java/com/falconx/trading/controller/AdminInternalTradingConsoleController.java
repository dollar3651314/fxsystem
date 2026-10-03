package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.api.AdminManualLiquidateRequest;
import com.falconx.trading.api.AdminManualLiquidateResponse;
import com.falconx.trading.api.AdminRiskSwitchUpdateRequest;
import com.falconx.trading.api.AdminRiskSwitchUpdateResponse;
import com.falconx.trading.api.AdminTradingExposureListResponse;
import com.falconx.trading.api.AdminTradingOrderListResponse;
import com.falconx.trading.api.AdminTradingPositionListResponse;
import com.falconx.trading.api.AdminTradingPositionSummaryResponse;
import com.falconx.trading.api.AdminTradingRiskSwitchListResponse;
import com.falconx.trading.application.TradingMonitorAdminApplicationService;
import com.falconx.trading.entity.TradingRiskSwitch;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-2-TRADING-MONITOR R4：trading-core 暴露给 console-service 的管理端 internal RPC。
 *
 * <p>路径前缀：{@code /internal/v1/trading/console/}（与 {@code /accounts} 平级）。
 *
 * <p>鉴权：{@link com.falconx.trading.security.TradingInternalApiTokenFilter} 校验
 * {@code X-Internal-Token} + {@code X-Admin-User-Id}。
 *
 * <p>契约：[`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §7。
 */
@RestController
@RequestMapping("/internal/v1/trading/console")
public class AdminInternalTradingConsoleController {

    private static final Logger log = LoggerFactory.getLogger(AdminInternalTradingConsoleController.class);

    private final TradingMonitorAdminApplicationService monitorService;
    private final com.falconx.trading.application.TradingPendingOrderAdminApplicationService pendingOrderAdminService;
    private final com.falconx.trading.application.TradingPriceAlertAdminApplicationService priceAlertAdminService;
    private final com.falconx.trading.application.TradingPlatformMetricsApplicationService platformMetricsService;
    private final com.falconx.trading.websocket.TradingRealtimeDualPnlSupport dualPnlSupport;

    public AdminInternalTradingConsoleController(TradingMonitorAdminApplicationService monitorService,
                                                  com.falconx.trading.application.TradingPendingOrderAdminApplicationService pendingOrderAdminService,
                                                  com.falconx.trading.application.TradingPriceAlertAdminApplicationService priceAlertAdminService,
                                                  com.falconx.trading.application.TradingPlatformMetricsApplicationService platformMetricsService,
                                                  com.falconx.trading.websocket.TradingRealtimeDualPnlSupport dualPnlSupport) {
        this.monitorService = monitorService;
        this.pendingOrderAdminService = pendingOrderAdminService;
        this.priceAlertAdminService = priceAlertAdminService;
        this.platformMetricsService = platformMetricsService;
        this.dualPnlSupport = dualPnlSupport;
    }

    @GetMapping("/orders")
    public ApiResponse<AdminTradingOrderListResponse> listOrders(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) OffsetDateTime fromCreatedAt,
            @RequestParam(required = false) OffsetDateTime toCreatedAt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return success(monitorService.listOrders(userId, symbol, status, fromCreatedAt, toCreatedAt, safePage, safeSize));
    }

    @GetMapping("/positions")
    public ApiResponse<AdminTradingPositionListResponse> listPositions(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) OffsetDateTime fromOpenedAt,
            @RequestParam(required = false) OffsetDateTime toOpenedAt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return success(monitorService.listPositions(userId, symbol, status, fromOpenedAt, toOpenedAt, safePage, safeSize));
    }

    @GetMapping("/positions/summary")
    public ApiResponse<AdminTradingPositionSummaryResponse> getPositionSummary() {
        return success(monitorService.getPositionSummary());
    }

    @GetMapping("/positions/{positionId}/swap-summary")
    public ApiResponse<com.falconx.trading.dto.TradingSwapSummaryResponse> getPositionSwapSummary(
            @PathVariable long positionId) {
        log.info("trading.internal.swap-summary.position.received positionId={}", positionId);
        return success(monitorService.getPositionSwapSummary(positionId));
    }

    @GetMapping("/platform/metrics-overview")
    public ApiResponse<com.falconx.trading.dto.TradingPlatformMetricsResponse> getPlatformMetricsOverview() {
        log.info("trading.internal.platform.metrics.received");
        return success(platformMetricsService.buildOverview());
    }

    @GetMapping("/exposures")
    public ApiResponse<AdminTradingExposureListResponse> listExposures(@RequestParam(required = false) String symbol) {
        return success(monitorService.listExposures(symbol));
    }

    @GetMapping("/risk-switches")
    public ApiResponse<AdminTradingRiskSwitchListResponse> listRiskSwitches() {
        return success(monitorService.listRiskSwitches());
    }

    @PostMapping("/positions/{positionId}/manual-liquidate")
    public ApiResponse<AdminManualLiquidateResponse> manualLiquidate(
            @PathVariable long positionId,
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @Valid @RequestBody AdminManualLiquidateRequest request) {
        log.info("trading.internal.manual-liquidate.received positionId={} adminUserId={}", positionId, adminUserId);
        return success(monitorService.manualLiquidate(positionId, adminUserId, request.reason()));
    }

    // STAGE-3-PENDING-ORDER：挂单监控 + 强制撤单

    @GetMapping("/pending-orders")
    public ApiResponse<com.falconx.trading.dto.TradingPendingOrderListResponse> listPendingOrders(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "false") boolean includeSlTp,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        java.util.List<com.falconx.trading.entity.TradingPendingOrderTrigger> items =
                pendingOrderAdminService.listAdmin(userId, symbol, status, includeSlTp, safePage, safeSize);
        long total = pendingOrderAdminService.countAdmin(userId, symbol, status, includeSlTp);
        java.util.List<com.falconx.trading.dto.TradingPendingOrderItemResponse> mapped = items.stream()
                .map(this::toPendingItem).toList();
        return success(new com.falconx.trading.dto.TradingPendingOrderListResponse(safePage, safeSize, total, mapped));
    }

    @PostMapping("/pending-orders/{id}/cancel")
    public ApiResponse<com.falconx.trading.dto.TradingPendingOrderItemResponse> cancelPendingOrderByAdmin(
            @PathVariable long id,
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @RequestBody java.util.Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        log.info("trading.internal.pending-order.admin-cancel.received id={} adminUserId={} reason={}",
                id, adminUserId, reason);
        com.falconx.trading.entity.TradingPendingOrderTrigger order =
                pendingOrderAdminService.cancelByAdmin(id, "ADMIN_FORCE_CANCEL:" + adminUserId
                        + (reason == null ? "" : ":" + reason));
        return success(toPendingItem(order));
    }

    private com.falconx.trading.dto.TradingPendingOrderItemResponse toPendingItem(
            com.falconx.trading.entity.TradingPendingOrderTrigger order) {
        return new com.falconx.trading.dto.TradingPendingOrderItemResponse(
                order.id(), order.orderNo(), order.symbol(),
                order.orderType().name(), order.side().name(),
                order.quantity(), order.triggerPrice(), order.limitPrice(),
                order.leverage(), order.marginMode() == null ? null : order.marginMode().name(),
                order.frozenMargin(), order.frozenFee(),
                order.status().name(), order.parentPositionId(),
                order.triggerKind() == null ? null : order.triggerKind().name(),
                order.clientOrderId(), order.triggeredOrderId(),
                order.triggeredAt(), order.cancelledAt(), order.cancelReason(),
                order.createdAt(), order.updatedAt(),
                dualPnlSupport.resolvePricePrecision(order.symbol()));
    }

    // STAGE-4-PRICE-ALERT：管理端告警监控 + 强制删除

    @GetMapping("/price-alerts")
    public ApiResponse<com.falconx.trading.dto.PriceAlertListResponse> listPriceAlerts(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        java.util.List<com.falconx.trading.entity.TradingPriceAlert> items =
                priceAlertAdminService.listAdmin(userId, symbol, status, safePage, safeSize);
        long total = priceAlertAdminService.countAdmin(userId, symbol, status);
        java.util.List<com.falconx.trading.dto.PriceAlertItemResponse> mapped = items.stream()
                .map(this::toPriceAlertItem).toList();
        return success(new com.falconx.trading.dto.PriceAlertListResponse(safePage, safeSize, total, mapped));
    }

    @PostMapping("/price-alerts/{id}/delete")
    public ApiResponse<com.falconx.trading.dto.PriceAlertItemResponse> deletePriceAlertByAdmin(
            @PathVariable long id,
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @RequestBody java.util.Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        log.info("trading.internal.price-alert.admin-delete.received id={} adminUserId={} reason={}",
                id, adminUserId, reason);
        com.falconx.trading.entity.TradingPriceAlert alert =
                priceAlertAdminService.forceDelete(id, adminUserId, reason);
        return success(toPriceAlertItem(alert));
    }

    private com.falconx.trading.dto.PriceAlertItemResponse toPriceAlertItem(
            com.falconx.trading.entity.TradingPriceAlert a) {
        int remaining = Math.max(0, 3 - a.triggerCount());
        return new com.falconx.trading.dto.PriceAlertItemResponse(
                String.valueOf(a.id()),
                String.valueOf(a.userId()),
                a.symbol(),
                a.direction().name(),
                a.targetPrice(),
                a.status().name(),
                a.note(),
                a.basePrice(),
                a.triggerCount(),
                remaining,
                a.lastTriggeredAt(),
                a.lastTriggeredPrice(),
                a.cancelledAt(),
                a.cancelSource(),
                a.createdAt(),
                a.updatedAt(),
                dualPnlSupport.resolvePricePrecision(a.symbol())
        );
    }

    @PostMapping("/risk-switches/auto-liquidate")
    public ApiResponse<AdminRiskSwitchUpdateResponse> updateAutoLiquidateSwitch(
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @Valid @RequestBody AdminRiskSwitchUpdateRequest request) {
        log.info("trading.internal.risk-switch.update.received key={} enabled={} adminUserId={}",
                TradingRiskSwitch.KEY_AUTO_LIQUIDATE_ENABLED, request.enabled(), adminUserId);
        return success(monitorService.updateRiskSwitch(
                TradingRiskSwitch.KEY_AUTO_LIQUIDATE_ENABLED,
                request.enabled(),
                request.reason(),
                adminUserId
        ));
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
