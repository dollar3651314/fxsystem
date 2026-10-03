package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminManualLiquidateRequest;
import com.falconx.console.api.AdminManualLiquidateResponse;
import com.falconx.console.api.AdminRiskSwitchUpdateRequest;
import com.falconx.console.api.AdminRiskSwitchUpdateResponse;
import com.falconx.console.api.AdminTradingExposureListResponse;
import com.falconx.console.api.AdminTradingOrderListResponse;
import com.falconx.console.api.AdminTradingPositionListResponse;
import com.falconx.console.api.AdminTradingPositionSummaryResponse;
import com.falconx.console.api.AdminTradingRiskSwitchListResponse;
import com.falconx.console.security.RequiresPermission;
import com.falconx.console.trading.AdminTradingMonitorApplicationService;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-2-TRADING-MONITOR R9：订单 / 持仓监控 + 风控开关管理 controller。
 *
 * <p>路由 / 权限：{@code /admin/trading/*}，详见 docs/api/管理端接口规范.md §7。
 * 高危写操作走 OperationAuditAspect 自动审计。
 */
@RestController
@RequestMapping("/admin/trading")
public class AdminTradingMonitorController {

    private static final Logger log = LoggerFactory.getLogger(AdminTradingMonitorController.class);

    private final AdminTradingMonitorApplicationService monitorService;

    public AdminTradingMonitorController(AdminTradingMonitorApplicationService monitorService) {
        this.monitorService = monitorService;
    }

    @GetMapping("/orders")
    @RequiresPermission(value = "trading-monitor:order:view", description = "查看订单列表")
    public ApiResponse<AdminTradingOrderListResponse> listOrders(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime fromCreatedAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime toCreatedAt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.trading.orders.received page={} size={}", page, size);
        return success(monitorService.listOrders(userId, symbol, status, fromCreatedAt, toCreatedAt, page, size));
    }

    @GetMapping("/positions")
    @RequiresPermission(value = "trading-monitor:position:view", description = "查看持仓列表")
    public ApiResponse<AdminTradingPositionListResponse> listPositions(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime fromOpenedAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime toOpenedAt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.trading.positions.received page={} size={}", page, size);
        return success(monitorService.listPositions(userId, symbol, status, fromOpenedAt, toOpenedAt, page, size));
    }

    @GetMapping("/positions/summary")
    @RequiresPermission(value = "trading-monitor:position:view", description = "查看平台持仓汇总")
    public ApiResponse<AdminTradingPositionSummaryResponse> getPositionSummary() {
        log.info("admin.http.trading.positions.summary.received");
        return success(monitorService.getPositionSummary());
    }

    @GetMapping("/positions/{positionId}/swap-summary")
    @RequiresPermission(value = "trading-monitor:position:view", description = "查看持仓 Swap 累计")
    public ApiResponse<com.falconx.console.api.AdminTradingSwapSummaryResponse> getPositionSwapSummary(
            @PathVariable long positionId) {
        log.info("admin.http.trading.swap-summary.received positionId={}", positionId);
        return success(monitorService.getPositionSwapSummary(positionId));
    }

    @GetMapping("/platform/metrics-overview")
    @RequiresPermission(value = "trading-monitor:position:view", description = "查看平台运营指标")
    public ApiResponse<com.falconx.console.api.AdminPlatformMetricsResponse> getPlatformMetricsOverview() {
        log.info("admin.http.trading.platform.metrics.received");
        return success(monitorService.getPlatformMetricsOverview());
    }

    @GetMapping("/exposures")
    @RequiresPermission(value = "trading-monitor:exposure:view", description = "查看净敞口看板")
    public ApiResponse<AdminTradingExposureListResponse> listExposures(@RequestParam(required = false) String symbol) {
        log.info("admin.http.trading.exposures.received symbol={}", symbol);
        return success(monitorService.listExposures(symbol));
    }

    @GetMapping("/risk-switches")
    @RequiresPermission(value = "trading-monitor:exposure:view", description = "查看风控开关")
    public ApiResponse<AdminTradingRiskSwitchListResponse> listRiskSwitches() {
        log.info("admin.http.trading.risk-switches.received");
        return success(monitorService.listRiskSwitches());
    }

    @PostMapping("/positions/{positionId}/manual-liquidate")
    @RequiresPermission(value = "trading-monitor:manual-liquidate", description = "手动强平指定持仓（高危）")
    public ApiResponse<AdminManualLiquidateResponse> manualLiquidate(
            @PathVariable long positionId,
            @Valid @RequestBody AdminManualLiquidateRequest request) {
        log.info("admin.http.trading.manual-liquidate.received positionId={}", positionId);
        return success(monitorService.manualLiquidate(positionId, request.reason()));
    }

    @PostMapping("/risk-switches/auto-liquidate")
    @RequiresPermission(value = "trading-monitor:auto-liquidate:pause", description = "暂停/恢复自动强平（高危）")
    public ApiResponse<AdminRiskSwitchUpdateResponse> updateAutoLiquidate(
            @Valid @RequestBody AdminRiskSwitchUpdateRequest request) {
        log.info("admin.http.trading.risk-switch.update.received enabled={}", request.enabled());
        return success(monitorService.updateAutoLiquidateSwitch(request.enabled(), request.reason()));
    }

    // STAGE-3-PENDING-ORDER

    @GetMapping("/pending-orders")
    @RequiresPermission(value = "trading-monitor:order:view", description = "查看挂单列表")
    public ApiResponse<com.falconx.console.api.AdminPendingOrderListResponse> listPendingOrders(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "false") boolean includeSlTp,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.trading.pending-orders.list.received userId={} symbol={} includeSlTp={}",
                userId, symbol, includeSlTp);
        return success(monitorService.listPendingOrders(userId, symbol, status, includeSlTp, page, size));
    }

    @PostMapping("/pending-orders/{id}/cancel")
    @RequiresPermission(value = "trading-monitor:manual-liquidate", description = "强制撤销挂单（高危）")
    public ApiResponse<com.falconx.console.api.AdminPendingOrderItem> cancelPendingOrder(
            @PathVariable long id,
            @Valid @RequestBody com.falconx.console.api.AdminPendingOrderCancelRequest request) {
        log.info("admin.http.trading.pending-orders.cancel.received id={} reason={}", id, request.reason());
        return success(monitorService.cancelPendingOrder(id, request.reason()));
    }

    // STAGE-4-PRICE-ALERT

    @GetMapping("/price-alerts")
    @RequiresPermission(value = "trading-monitor:order:view", description = "查看价格告警列表")
    public ApiResponse<com.falconx.console.api.AdminPriceAlertListResponse> listPriceAlerts(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.trading.price-alerts.list.received userId={} symbol={}", userId, symbol);
        return success(monitorService.listPriceAlerts(userId, symbol, status, page, size));
    }

    @PostMapping("/price-alerts/{id}/delete")
    @RequiresPermission(value = "trading-monitor:manual-liquidate", description = "强制删除价格告警（高危）")
    public ApiResponse<com.falconx.console.api.AdminPriceAlertItem> deletePriceAlert(
            @PathVariable long id,
            @Valid @RequestBody com.falconx.console.api.AdminPriceAlertDeleteRequest request) {
        log.info("admin.http.trading.price-alerts.delete.received id={} reason={}", id, request.reason());
        return success(monitorService.deletePriceAlert(id, request.reason()));
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
