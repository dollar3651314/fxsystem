package com.falconx.console.trading;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminManualLiquidateResponse;
import com.falconx.console.api.AdminRiskSwitchUpdateResponse;
import com.falconx.console.api.AdminTradingExposureListResponse;
import com.falconx.console.api.AdminTradingOrderListResponse;
import com.falconx.console.api.AdminTradingPositionListResponse;
import com.falconx.console.api.AdminTradingPositionSummaryResponse;
import com.falconx.console.api.AdminTradingRiskSwitchListResponse;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.AdminUserInfoEnricher;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * STAGE-2-TRADING-MONITOR R9：管理端订单 / 持仓 / 敞口 / 风控开关编排。
 *
 * <p>转发到 trading-core internal RPC（{@code /internal/v1/trading/console/*}），
 * 错误码翻译 90650-90656；高危写操作（manual-liquidate / risk-switch）由 OperationAuditAspect
 * 自动写 t_admin_operation_log。
 */
@Service
public class AdminTradingMonitorApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminTradingMonitorApplicationService.class);

    private static final ParameterizedTypeReference<ApiResponse<AdminTradingOrderListResponse>> ORDER_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminTradingPositionListResponse>> POSITION_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminTradingPositionSummaryResponse>> POSITION_SUMMARY_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminTradingExposureListResponse>> EXPOSURE_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminTradingRiskSwitchListResponse>> RISK_SWITCH_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminManualLiquidateResponse>> MANUAL_LIQUIDATE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminRiskSwitchUpdateResponse>> RISK_SWITCH_UPDATE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<com.falconx.console.api.AdminPendingOrderListResponse>> PENDING_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<com.falconx.console.api.AdminPendingOrderItem>> PENDING_ITEM_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<com.falconx.console.api.AdminPriceAlertListResponse>> PRICE_ALERT_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<com.falconx.console.api.AdminPriceAlertItem>> PRICE_ALERT_ITEM_TYPE =
            new ParameterizedTypeReference<>() {};

    private final InternalRpcClient internalRpcClient;
    private final AdminUserInfoEnricher userInfoEnricher;

    public AdminTradingMonitorApplicationService(InternalRpcClient internalRpcClient,
                                                 AdminUserInfoEnricher userInfoEnricher) {
        this.internalRpcClient = internalRpcClient;
        this.userInfoEnricher = userInfoEnricher;
    }

    public AdminTradingOrderListResponse listOrders(Long userId, String symbol, Integer status,
                                                    OffsetDateTime fromCreatedAt, OffsetDateTime toCreatedAt,
                                                    int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/trading/console/orders?page=")
                .append(page).append("&size=").append(size);
        if (userId != null) query.append("&userId=").append(userId);
        if (symbol != null && !symbol.isBlank()) query.append("&symbol=").append(symbol);
        if (status != null) query.append("&status=").append(status);
        if (fromCreatedAt != null) query.append("&fromCreatedAt=").append(formatIso(fromCreatedAt));
        if (toCreatedAt != null) query.append("&toCreatedAt=").append(formatIso(toCreatedAt));
        try {
            AdminTradingOrderListResponse resp = internalRpcClient.get(query.toString(), ORDER_LIST_TYPE);
            List<AdminTradingOrderListResponse.Item> items = userInfoEnricher.enrich(
                    resp.items(), AdminTradingOrderListResponse.Item::userId,
                    (it, r) -> it.withUserInfo(r.uid(), r.email(), r.fullName()));
            return new AdminTradingOrderListResponse(items, resp.total(), resp.page(), resp.size());
        } catch (InternalRpcException ex) {
            translateTradingError(ex);
            throw ex;
        }
    }

    public AdminTradingPositionListResponse listPositions(Long userId, String symbol, Integer status,
                                                          OffsetDateTime fromOpenedAt, OffsetDateTime toOpenedAt,
                                                          int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/trading/console/positions?page=")
                .append(page).append("&size=").append(size);
        if (userId != null) query.append("&userId=").append(userId);
        if (symbol != null && !symbol.isBlank()) query.append("&symbol=").append(symbol);
        if (status != null) query.append("&status=").append(status);
        if (fromOpenedAt != null) query.append("&fromOpenedAt=").append(formatIso(fromOpenedAt));
        if (toOpenedAt != null) query.append("&toOpenedAt=").append(formatIso(toOpenedAt));
        try {
            AdminTradingPositionListResponse resp = internalRpcClient.get(query.toString(), POSITION_LIST_TYPE);
            List<AdminTradingPositionListResponse.Item> items = userInfoEnricher.enrich(
                    resp.items(), AdminTradingPositionListResponse.Item::userId,
                    (it, r) -> it.withUserInfo(r.uid(), r.email(), r.fullName()));
            return new AdminTradingPositionListResponse(items, resp.total(), resp.page(), resp.size());
        } catch (InternalRpcException ex) {
            translateTradingError(ex);
            throw ex;
        }
    }

    public AdminTradingPositionSummaryResponse getPositionSummary() {
        try {
            return internalRpcClient.get("/internal/v1/trading/console/positions/summary", POSITION_SUMMARY_TYPE);
        } catch (InternalRpcException ex) {
            translateTradingError(ex);
            throw ex;
        }
    }

    private static final ParameterizedTypeReference<ApiResponse<com.falconx.console.api.AdminTradingSwapSummaryResponse>> SWAP_SUMMARY_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<com.falconx.console.api.AdminPlatformMetricsResponse>> PLATFORM_METRICS_TYPE =
            new ParameterizedTypeReference<>() {};

    public com.falconx.console.api.AdminTradingSwapSummaryResponse getPositionSwapSummary(long positionId) {
        try {
            return internalRpcClient.get(
                    "/internal/v1/trading/console/positions/" + positionId + "/swap-summary",
                    SWAP_SUMMARY_TYPE);
        } catch (InternalRpcException ex) {
            translateTradingError(ex);
            throw ex;
        }
    }

    public com.falconx.console.api.AdminPlatformMetricsResponse getPlatformMetricsOverview() {
        try {
            return internalRpcClient.get(
                    "/internal/v1/trading/console/platform/metrics-overview",
                    PLATFORM_METRICS_TYPE);
        } catch (InternalRpcException ex) {
            translateTradingError(ex);
            throw ex;
        }
    }

    public AdminTradingExposureListResponse listExposures(String symbol) {
        String path = "/internal/v1/trading/console/exposures";
        if (symbol != null && !symbol.isBlank()) {
            path = path + "?symbol=" + symbol;
        }
        try {
            return internalRpcClient.get(path, EXPOSURE_LIST_TYPE);
        } catch (InternalRpcException ex) {
            translateTradingError(ex);
            throw ex;
        }
    }

    public AdminTradingRiskSwitchListResponse listRiskSwitches() {
        try {
            return internalRpcClient.get("/internal/v1/trading/console/risk-switches", RISK_SWITCH_LIST_TYPE);
        } catch (InternalRpcException ex) {
            translateTradingError(ex);
            throw ex;
        }
    }

    public AdminManualLiquidateResponse manualLiquidate(long positionId, String reason) {
        verifyReason(reason);
        Map<String, Object> body = Map.of("reason", reason);
        try {
            AdminManualLiquidateResponse response = internalRpcClient.post(
                    "/internal/v1/trading/console/positions/" + positionId + "/manual-liquidate",
                    body,
                    MANUAL_LIQUIDATE_TYPE);
            log.info("admin.trading.manual-liquidate.completed positionId={} closedAt={}",
                    positionId, response.closedAt());
            com.falconx.console.security.AuditSnapshotHolder.set(
                    Map.of("positionId", positionId, "status", "OPEN"),
                    Map.of("status", "CLOSED", "closedAt", String.valueOf(response.closedAt()),
                            "realizedPnl", String.valueOf(response.realizedPnl()),
                            "action", "manual-liquidate", "reason", reason)
            );
            return response;
        } catch (InternalRpcException ex) {
            translateTradingError(ex);
            throw ex;
        }
    }

    public AdminRiskSwitchUpdateResponse updateAutoLiquidateSwitch(boolean enabled, String reason) {
        verifyReason(reason);
        Map<String, Object> body = Map.of("enabled", enabled, "reason", reason);
        try {
            AdminRiskSwitchUpdateResponse response = internalRpcClient.post(
                    "/internal/v1/trading/console/risk-switches/auto-liquidate",
                    body,
                    RISK_SWITCH_UPDATE_TYPE);
            log.info("admin.trading.risk-switch.update.completed key={} enabled={}", response.key(), response.enabled());
            com.falconx.console.security.AuditSnapshotHolder.set(
                    Map.of("key", response.key(), "enabled", !enabled),
                    Map.of("key", response.key(), "enabled", enabled, "reason", reason)
            );
            return response;
        } catch (InternalRpcException ex) {
            translateTradingError(ex);
            throw ex;
        }
    }

    // STAGE-3-PENDING-ORDER

    public com.falconx.console.api.AdminPendingOrderListResponse listPendingOrders(
            Long userId, String symbol, Integer status, boolean includeSlTp, int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/trading/console/pending-orders?page=")
                .append(page).append("&size=").append(size).append("&includeSlTp=").append(includeSlTp);
        if (userId != null) query.append("&userId=").append(userId);
        if (symbol != null && !symbol.isBlank()) query.append("&symbol=").append(symbol);
        if (status != null) query.append("&status=").append(status);
        try {
            return internalRpcClient.get(query.toString(), PENDING_LIST_TYPE);
        } catch (InternalRpcException ex) {
            translateTradingError(ex);
            throw ex;
        }
    }

    public com.falconx.console.api.AdminPendingOrderItem cancelPendingOrder(long id, String reason) {
        verifyReason(reason);
        try {
            com.falconx.console.api.AdminPendingOrderItem item = internalRpcClient.post(
                    "/internal/v1/trading/console/pending-orders/" + id + "/cancel",
                    Map.of("reason", reason), PENDING_ITEM_TYPE);
            log.info("admin.pending-order.cancel.completed id={} reason={}", id, reason);
            com.falconx.console.security.AuditSnapshotHolder.set(
                    Map.of("pendingOrderId", id, "status", "PENDING"),
                    Map.of("status", String.valueOf(item.status()), "symbol", String.valueOf(item.symbol()),
                            "action", "force-cancel", "reason", reason)
            );
            return item;
        } catch (InternalRpcException ex) {
            translateTradingError(ex);
            throw ex;
        }
    }

    // STAGE-4-PRICE-ALERT

    public com.falconx.console.api.AdminPriceAlertListResponse listPriceAlerts(
            Long userId, String symbol, Integer status, int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/trading/console/price-alerts?page=")
                .append(page).append("&size=").append(size);
        if (userId != null) query.append("&userId=").append(userId);
        if (symbol != null && !symbol.isBlank()) query.append("&symbol=").append(symbol);
        if (status != null) query.append("&status=").append(status);
        try {
            com.falconx.console.api.AdminPriceAlertListResponse resp =
                    internalRpcClient.get(query.toString(), PRICE_ALERT_LIST_TYPE);
            List<com.falconx.console.api.AdminPriceAlertItem> items = userInfoEnricher.enrich(
                    resp.items(), it -> Long.parseLong(it.userId()),
                    (it, r) -> it.withUserInfo(r.uid(), r.email(), r.fullName()));
            return new com.falconx.console.api.AdminPriceAlertListResponse(
                    resp.page(), resp.pageSize(), resp.total(), items);
        } catch (InternalRpcException ex) {
            translateTradingError(ex);
            throw ex;
        }
    }

    public com.falconx.console.api.AdminPriceAlertItem deletePriceAlert(long id, String reason) {
        verifyReason(reason);
        try {
            com.falconx.console.api.AdminPriceAlertItem item = internalRpcClient.post(
                    "/internal/v1/trading/console/price-alerts/" + id + "/delete",
                    Map.of("reason", reason), PRICE_ALERT_ITEM_TYPE);
            log.info("admin.price-alert.delete.completed id={} reason={}", id, reason);
            com.falconx.console.security.AuditSnapshotHolder.set(
                    Map.of("priceAlertId", id, "status", "ACTIVE"),
                    Map.of("status", String.valueOf(item.status()), "symbol", String.valueOf(item.symbol()),
                            "action", "force-delete", "reason", reason)
            );
            return item;
        } catch (InternalRpcException ex) {
            translateTradingError(ex);
            throw ex;
        }
    }

    private void verifyReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_TRADING_REASON_REQUIRED);
        }
    }

    private static String formatIso(OffsetDateTime ts) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ts);
    }

    /** 把 trading-core 业务码翻译为 console 错误码（90650-90655 透传）。 */
    private void translateTradingError(InternalRpcException ex) {
        String code = ex.getDownstreamCode();
        if (code == null) return;
        switch (code) {
            case "90650" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_TRADING_POSITION_NOT_FOUND);
            case "90651" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_TRADING_POSITION_ALREADY_CLOSED);
            case "90652" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_TRADING_POSITION_LOCK_TIMEOUT);
            case "90653" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_TRADING_MANUAL_LIQUIDATE_FAILED);
            case "90654" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_TRADING_RISK_SWITCH_KEY_INVALID);
            case "90655" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_TRADING_RISK_SWITCH_VALUE_UNCHANGED);
            default -> { /* 其他错误透传原始 InternalRpcException */ }
        }
    }
}
