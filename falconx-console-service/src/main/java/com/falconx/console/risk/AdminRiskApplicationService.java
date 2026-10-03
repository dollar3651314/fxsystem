package com.falconx.console.risk;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminRiskActionActivateRequest;
import com.falconx.console.api.AdminRiskActionDeactivateRequest;
import com.falconx.console.api.AdminRiskActionListResponse;
import com.falconx.console.api.AdminRiskConfigCreateRequest;
import com.falconx.console.api.AdminRiskConfigDeleteRequest;
import com.falconx.console.api.AdminRiskConfigListResponse;
import com.falconx.console.api.AdminRiskConfigUpdateRequest;
import com.falconx.console.api.AdminDirectionImbalanceUpdateRequest;
import com.falconx.console.api.AdminPlatformRiskConfigUpdateRequest;
import com.falconx.console.api.AdminRiskMarketConfigListResponse;
import com.falconx.console.api.AdminRiskMarketConfigUpdateRequest;
import com.falconx.console.api.AdminSymbolQuoteMappingListResponse;
import com.falconx.console.api.AdminUserRiskThresholdItem;
import com.falconx.console.api.AdminUserRiskThresholdListResponse;
import com.falconx.console.api.AdminUserRiskThresholdUpsertRequest;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.internal.AdminUserInfoEnricher;
import com.falconx.console.internal.InternalRpcClient;
import com.falconx.console.internal.InternalRpcException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * STAGE-2-RISK-ADMIN R9：风控管理编排。
 *
 * <p>所有调用透传到 trading-core /internal/v1/trading/console/risk-*；
 * 错误码 90800-90810 翻译；高危写操作走 OperationAuditAspect 审计。
 */
@Service
public class AdminRiskApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminRiskApplicationService.class);

    private static final ParameterizedTypeReference<ApiResponse<AdminRiskActionListResponse>> RISK_ACTION_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminRiskActionListResponse.Item>> RISK_ACTION_ITEM_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminRiskConfigListResponse>> RISK_CONFIG_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminRiskConfigListResponse.Item>> RISK_CONFIG_ITEM_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminRiskMarketConfigListResponse>> RISK_MARKET_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminRiskMarketConfigListResponse.Item>> RISK_MARKET_ITEM_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminUserRiskThresholdListResponse>> USER_RISK_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminUserRiskThresholdItem>> USER_RISK_ITEM_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<AdminSymbolQuoteMappingListResponse>> MAPPING_LIST_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final InternalRpcClient internalRpcClient;
    private final AdminUserInfoEnricher userInfoEnricher;

    public AdminRiskApplicationService(InternalRpcClient internalRpcClient,
                                       AdminUserInfoEnricher userInfoEnricher) {
        this.internalRpcClient = internalRpcClient;
        this.userInfoEnricher = userInfoEnricher;
    }

    // ---- risk-actions ----

    public AdminRiskActionListResponse listRiskActions(String symbol, String actionType, String triggerSource,
                                                       Boolean isActive, int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/trading/console/risk-actions?page=")
                .append(page).append("&size=").append(size);
        if (symbol != null && !symbol.isBlank()) query.append("&symbol=").append(symbol);
        if (actionType != null && !actionType.isBlank()) query.append("&actionType=").append(actionType);
        if (triggerSource != null && !triggerSource.isBlank()) query.append("&triggerSource=").append(triggerSource);
        if (isActive != null) query.append("&isActive=").append(isActive);
        try {
            return internalRpcClient.get(query.toString(), RISK_ACTION_LIST_TYPE);
        } catch (InternalRpcException ex) {
            translateRiskError(ex);
            throw ex;
        }
    }

    public AdminRiskActionListResponse.Item activateRiskAction(AdminRiskActionActivateRequest request) {
        verifyReason(request.reason());
        if (request.symbol() != null && !request.symbol().isBlank()) {
            requirePlatformMapping(request.symbol());
        }
        try {
            AdminRiskActionListResponse.Item item = internalRpcClient.post("/internal/v1/trading/console/risk-actions",
                    Map.of(
                            "symbol", request.symbol() == null ? "" : request.symbol(),
                            "actionType", request.actionType(),
                            "reason", request.reason()
                    ),
                    RISK_ACTION_ITEM_TYPE);
            com.falconx.console.security.AuditSnapshotHolder.set(
                    null,
                    Map.of("symbol", request.symbol() == null ? "" : request.symbol(),
                            "actionType", request.actionType(),
                            "action", "activate", "reason", request.reason())
            );
            return item;
        } catch (InternalRpcException ex) {
            translateRiskError(ex);
            throw ex;
        }
    }

    public AdminRiskActionListResponse.Item deactivateRiskAction(long id, AdminRiskActionDeactivateRequest request) {
        verifyReason(request.reason());
        try {
            AdminRiskActionListResponse.Item item = internalRpcClient.post(
                    "/internal/v1/trading/console/risk-actions/" + id + "/deactivate",
                    Map.of("reason", request.reason()),
                    RISK_ACTION_ITEM_TYPE);
            com.falconx.console.security.AuditSnapshotHolder.set(
                    Map.of("riskActionId", id, "status", "ACTIVE"),
                    Map.of("riskActionId", id, "status", "INACTIVE",
                            "action", "deactivate", "reason", request.reason())
            );
            return item;
        } catch (InternalRpcException ex) {
            translateRiskError(ex);
            throw ex;
        }
    }

    // ---- risk-configs ----

    public AdminRiskConfigListResponse listRiskConfigs(String symbol, String marketCode, int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/trading/console/risk-configs?page=")
                .append(page).append("&size=").append(size);
        if (symbol != null && !symbol.isBlank()) query.append("&symbol=").append(symbol);
        if (marketCode != null && !marketCode.isBlank()) query.append("&marketCode=").append(marketCode);
        try {
            return internalRpcClient.get(query.toString(), RISK_CONFIG_LIST_TYPE);
        } catch (InternalRpcException ex) {
            translateRiskError(ex);
            throw ex;
        }
    }

    public AdminRiskConfigListResponse.Item getRiskConfig(String symbol) {
        try {
            return internalRpcClient.get("/internal/v1/trading/console/risk-configs/" + symbol, RISK_CONFIG_ITEM_TYPE);
        } catch (InternalRpcException ex) {
            translateRiskError(ex);
            throw ex;
        }
    }

    public AdminRiskConfigListResponse.Item createRiskConfig(AdminRiskConfigCreateRequest request) {
        verifyReason(request.reason());
        AdminSymbolQuoteMappingListResponse.Item mapping = requirePlatformMapping(request.symbol());
        Map<String, Object> body = Map.of(
                "symbol", mapping.platformSymbol(),
                "marketCode", mapping.marketCode(),
                "maxPositionPerUser", request.maxPositionPerUser(),
                "maxPositionTotal", request.maxPositionTotal(),
                "maintenanceMarginRate", request.maintenanceMarginRate(),
                "maxLeverage", request.maxLeverage(),
                "hedgeThresholdUsd", request.hedgeThresholdUsd(),
                "reason", request.reason()
        );
        try {
            return internalRpcClient.post("/internal/v1/trading/console/risk-configs",
                    body, RISK_CONFIG_ITEM_TYPE);
        } catch (InternalRpcException ex) {
            translateRiskError(ex);
            throw ex;
        }
    }

    public AdminRiskConfigListResponse.Item updateRiskConfig(String symbol, AdminRiskConfigUpdateRequest request) {
        verifyReason(request.reason());
        requirePlatformMapping(symbol);
        try {
            return internalRpcClient.put("/internal/v1/trading/console/risk-configs/" + symbol,
                    request, RISK_CONFIG_ITEM_TYPE);
        } catch (InternalRpcException ex) {
            translateRiskError(ex);
            throw ex;
        }
    }

    public void deleteRiskConfig(String symbol, AdminRiskConfigDeleteRequest request) {
        verifyReason(request.reason());
        try {
            // InternalRpcClient 没有 DELETE，复用 POST 转 /risk-configs/{symbol}/delete 不合适；
            // 这里直接使用 POST + 与 trading-core DELETE 不匹配 —— 让 trading-core 也开 POST? 不,
            // 用更通用做法：以 POST /delete 子路径调用？
            // 实际：扩 InternalRpcClient 增 delete 方法，最小工作量。
            internalRpcClient.deleteWithBody(
                    "/internal/v1/trading/console/risk-configs/" + symbol,
                    Map.of("reason", request.reason()),
                    VOID_TYPE);
            log.info("admin.risk-config.deleted symbol={}", symbol);
        } catch (InternalRpcException ex) {
            translateRiskError(ex);
            throw ex;
        }
    }

    // ---- risk-market-configs ----

    public AdminRiskMarketConfigListResponse listRiskMarketConfigs() {
        try {
            return internalRpcClient.get("/internal/v1/trading/console/risk-market-configs", RISK_MARKET_LIST_TYPE);
        } catch (InternalRpcException ex) {
            translateRiskError(ex);
            throw ex;
        }
    }

    public AdminRiskMarketConfigListResponse.Item updateRiskMarketConfig(String marketCode,
                                                                          AdminRiskMarketConfigUpdateRequest request) {
        verifyReason(request.reason());
        try {
            return internalRpcClient.put(
                    "/internal/v1/trading/console/risk-market-configs/" + marketCode,
                    request, RISK_MARKET_ITEM_TYPE);
        } catch (InternalRpcException ex) {
            translateRiskError(ex);
            throw ex;
        }
    }

    // ---- BBOOK-RISK-CONTROL-01：平台 / 方向集中度 / 用户阈值 ----

    public void updatePlatformRiskConfig(AdminPlatformRiskConfigUpdateRequest request) {
        verifyReason(request.reason());
        try {
            internalRpcClient.post("/internal/v1/trading/console/risk-config/platform",
                    request, VOID_TYPE);
            log.info("admin.risk-config.platform.updated hedgeThresholdUsd={}", request.hedgeThresholdUsd());
        } catch (InternalRpcException ex) {
            translateRiskError(ex);
            throw ex;
        }
    }

    public void updateDirectionImbalance(String symbol, AdminDirectionImbalanceUpdateRequest request) {
        verifyReason(request.reason());
        requirePlatformMapping(symbol);
        try {
            internalRpcClient.post("/internal/v1/trading/console/risk-config/" + symbol + "/direction-imbalance",
                    request, VOID_TYPE);
            log.info("admin.risk-config.direction-imbalance.updated symbol={} ratio={}",
                    symbol, request.ratioThreshold());
        } catch (InternalRpcException ex) {
            translateRiskError(ex);
            throw ex;
        }
    }

    public AdminUserRiskThresholdListResponse listUserRiskThresholds(Long userId, int page, int size) {
        StringBuilder query = new StringBuilder("/internal/v1/trading/console/user-risk-thresholds?page=")
                .append(page).append("&size=").append(size);
        if (userId != null) query.append("&userId=").append(userId);
        try {
            AdminUserRiskThresholdListResponse resp = internalRpcClient.get(query.toString(), USER_RISK_LIST_TYPE);
            List<AdminUserRiskThresholdItem> items = userInfoEnricher.enrich(
                    resp.items(), AdminUserRiskThresholdItem::userId,
                    (it, r) -> it.withUserInfo(r.uid(), r.email(), r.fullName()));
            return new AdminUserRiskThresholdListResponse(items, resp.total(), resp.page(), resp.size());
        } catch (InternalRpcException ex) {
            translateRiskError(ex);
            throw ex;
        }
    }

    public AdminUserRiskThresholdItem upsertUserRiskThreshold(AdminUserRiskThresholdUpsertRequest request) {
        verifyReason(request.reason());
        try {
            return internalRpcClient.post("/internal/v1/trading/console/user-risk-thresholds",
                    request, USER_RISK_ITEM_TYPE);
        } catch (InternalRpcException ex) {
            translateRiskError(ex);
            throw ex;
        }
    }

    public void deleteUserRiskThreshold(long userId) {
        try {
            internalRpcClient.deleteWithBody(
                    "/internal/v1/trading/console/user-risk-thresholds/" + userId,
                    Map.of(),
                    VOID_TYPE);
            log.info("admin.user-risk-threshold.deleted userId={}", userId);
        } catch (InternalRpcException ex) {
            translateRiskError(ex);
            throw ex;
        }
    }

    // ---- helpers ----

    private void verifyReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_RISK_REASON_REQUIRED);
        }
    }

    private AdminSymbolQuoteMappingListResponse.Item requirePlatformMapping(String symbol) {
        String normalized = symbol == null ? "" : symbol.trim();
        if (normalized.isEmpty()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_MAPPING_NOT_FOUND);
        }
        try {
            String encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8);
            AdminSymbolQuoteMappingListResponse response = internalRpcClient.get(
                    "/internal/v1/market/symbols/quote-mappings?platformSymbolLike=" + encoded + "&page=0&size=100",
                    MAPPING_LIST_RESPONSE_TYPE);
            return response.items().stream()
                    .filter(item -> normalized.equals(item.platformSymbol()))
                    .findFirst()
                    .orElseThrow(() -> new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_MAPPING_NOT_FOUND));
        } catch (InternalRpcException ex) {
            if ("90613".equals(ex.getDownstreamCode())) {
                throw new AdminBusinessException(AdminErrorCode.ADMIN_SYMBOL_MAPPING_NOT_FOUND);
            }
            throw ex;
        }
    }

    private void translateRiskError(InternalRpcException ex) {
        String code = ex.getDownstreamCode();
        if (code == null) return;
        switch (code) {
            case "90800" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_RISK_ACTION_ALREADY_ACTIVE);
            case "90801" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_RISK_ACTION_NOT_FOUND);
            case "90802" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_RISK_ACTION_NOT_DEACTIVATABLE);
            case "90803" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_RISK_CONFIG_NOT_FOUND);
            case "90804" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_RISK_CONFIG_DUPLICATE_SYMBOL);
            case "90805" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_RISK_CONFIG_INVALID_LEVERAGE);
            case "90806" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_RISK_CONFIG_INVALID_POSITION_LIMIT);
            case "90807" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_RISK_CONFIG_INVALID_HEDGE_THRESHOLD);
            case "90808" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_RISK_MARKET_CONFIG_NOT_FOUND);
            case "90809" -> throw new AdminBusinessException(AdminErrorCode.ADMIN_RISK_MARKET_CONFIG_INVALID_THRESHOLD);
            default -> { /* 其他错误透传 */ }
        }
    }
}
