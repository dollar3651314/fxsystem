package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.api.AdminRiskActionActivateRequest;
import com.falconx.trading.api.AdminRiskActionDeactivateRequest;
import com.falconx.trading.api.AdminRiskActionListResponse;
import com.falconx.trading.api.AdminRiskConfigCreateRequest;
import com.falconx.trading.api.AdminRiskConfigDeleteRequest;
import com.falconx.trading.api.AdminRiskConfigListResponse;
import com.falconx.trading.api.AdminRiskConfigUpdateRequest;
import com.falconx.trading.api.AdminDirectionImbalanceUpdateRequest;
import com.falconx.trading.api.AdminPlatformRiskConfigUpdateRequest;
import com.falconx.trading.api.AdminRiskMarketConfigListResponse;
import com.falconx.trading.api.AdminRiskMarketConfigUpdateRequest;
import com.falconx.trading.api.AdminUserRiskThresholdItem;
import com.falconx.trading.api.AdminUserRiskThresholdListResponse;
import com.falconx.trading.api.AdminUserRiskThresholdUpsertRequest;
import com.falconx.trading.application.TradingRiskAdminApplicationService;
import com.falconx.trading.application.TradingBBookRiskControlAdminApplicationService;
import com.falconx.trading.entity.TradingRiskControlAction;
import com.falconx.trading.entity.TradingRiskControlActionType;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-2-RISK-ADMIN R4：风控管理 internal RPC。
 *
 * <p>路径前缀：{@code /internal/v1/trading/console/risk-*}（与 5.4 同 token + X-Admin-User-Id）。
 * 契约：[`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §8。
 */
@RestController
@RequestMapping("/internal/v1/trading/console")
public class AdminInternalTradingRiskController {

    private static final Logger log = LoggerFactory.getLogger(AdminInternalTradingRiskController.class);

    private final TradingRiskAdminApplicationService riskAdminService;
    private final TradingBBookRiskControlAdminApplicationService bbookRiskAdminService;

    public AdminInternalTradingRiskController(TradingRiskAdminApplicationService riskAdminService,
                                              TradingBBookRiskControlAdminApplicationService bbookRiskAdminService) {
        this.riskAdminService = riskAdminService;
        this.bbookRiskAdminService = bbookRiskAdminService;
    }

    // ---- risk-actions ----

    @GetMapping("/risk-actions")
    public ApiResponse<AdminRiskActionListResponse> listRiskActions(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) String triggerSource,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.OffsetDateTime fromCreatedAt,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.OffsetDateTime toCreatedAt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        TradingRiskControlActionType type = actionType == null || actionType.isBlank()
                ? null : TradingRiskControlActionType.valueOf(actionType);
        return success(riskAdminService.listRiskActions(
                symbol, type, triggerSource, isActive, fromCreatedAt, toCreatedAt, safePage, safeSize));
    }

    @PostMapping("/risk-actions")
    public ApiResponse<AdminRiskActionListResponse.Item> activateRiskAction(
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @Valid @RequestBody AdminRiskActionActivateRequest request) {
        TradingRiskControlActionType type = TradingRiskControlActionType.valueOf(request.actionType());
        log.info("trading.internal.risk-action.activate.received symbol={} actionType={} adminUserId={}",
                request.symbol(), type, adminUserId);
        TradingRiskControlAction activated = riskAdminService.activateRiskAction(request.symbol(), type, request.reason());
        return success(new AdminRiskActionListResponse.Item(
                activated.actionId(),
                activated.symbol(),
                activated.actionType().name(),
                activated.triggerSource(),
                activated.triggerReason(),
                activated.hedgeLogId(),
                activated.active(),
                null, null
        ));
    }

    @PostMapping("/risk-actions/{id}/deactivate")
    public ApiResponse<AdminRiskActionListResponse.Item> deactivateRiskAction(
            @PathVariable long id,
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @Valid @RequestBody AdminRiskActionDeactivateRequest request) {
        log.info("trading.internal.risk-action.deactivate.received id={} adminUserId={}", id, adminUserId);
        TradingRiskControlAction d = riskAdminService.deactivateRiskAction(id, request.reason());
        return success(new AdminRiskActionListResponse.Item(
                d.actionId(), d.symbol(), d.actionType().name(), d.triggerSource(),
                d.triggerReason(), d.hedgeLogId(), d.active(),
                d.createdAt() == null ? null : d.createdAt().toLocalDateTime(),
                d.updatedAt() == null ? null : d.updatedAt().toLocalDateTime()
        ));
    }

    // ---- risk-configs ----

    @GetMapping("/risk-configs")
    public ApiResponse<AdminRiskConfigListResponse> listRiskConfigs(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String marketCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return success(riskAdminService.listRiskConfigs(symbol, marketCode, safePage, safeSize));
    }

    @GetMapping("/risk-configs/{symbol}")
    public ApiResponse<AdminRiskConfigListResponse.Item> getRiskConfig(@PathVariable String symbol) {
        return success(riskAdminService.getRiskConfig(symbol));
    }

    @PostMapping("/risk-configs")
    public ApiResponse<AdminRiskConfigListResponse.Item> createRiskConfig(
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @Valid @RequestBody AdminRiskConfigCreateRequest request) {
        log.info("trading.internal.risk-config.create.received symbol={} adminUserId={}", request.symbol(), adminUserId);
        return success(riskAdminService.createRiskConfig(
                request.symbol(), request.marketCode(),
                request.maxPositionPerUser(), request.maxPositionTotal(),
                request.maintenanceMarginRate(), request.maxLeverage(),
                request.hedgeThresholdUsd()));
    }

    @PutMapping("/risk-configs/{symbol}")
    public ApiResponse<AdminRiskConfigListResponse.Item> updateRiskConfig(
            @PathVariable String symbol,
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @Valid @RequestBody AdminRiskConfigUpdateRequest request) {
        log.info("trading.internal.risk-config.update.received symbol={} adminUserId={}", symbol, adminUserId);
        return success(riskAdminService.updateRiskConfig(
                symbol, request.maxPositionPerUser(), request.maxPositionTotal(),
                request.maxLeverage(), request.hedgeThresholdUsd()));
    }

    @DeleteMapping("/risk-configs/{symbol}")
    public ApiResponse<Void> deleteRiskConfig(
            @PathVariable String symbol,
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @Valid @RequestBody AdminRiskConfigDeleteRequest request) {
        log.info("trading.internal.risk-config.delete.received symbol={} adminUserId={} reason={}",
                symbol, adminUserId, request.reason());
        riskAdminService.deleteRiskConfig(symbol);
        return success(null);
    }

    // ---- risk-market-configs ----

    @GetMapping("/risk-market-configs")
    public ApiResponse<AdminRiskMarketConfigListResponse> listRiskMarketConfigs() {
        return success(riskAdminService.listRiskMarketConfigs());
    }

    @PutMapping("/risk-market-configs/{marketCode}")
    public ApiResponse<AdminRiskMarketConfigListResponse.Item> updateRiskMarketConfig(
            @PathVariable String marketCode,
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @Valid @RequestBody AdminRiskMarketConfigUpdateRequest request) {
        log.info("trading.internal.risk-market-config.update.received marketCode={} adminUserId={}",
                marketCode, adminUserId);
        return success(riskAdminService.updateRiskMarketConfig(
                marketCode, request.concentrationThresholdUsd(), request.isEnabled()));
    }

    // ---- BBOOK-RISK-CONTROL-01：平台敞口 + 方向集中度 + 用户阈值 ----

    @PostMapping("/risk-config/platform")
    public ApiResponse<Void> updatePlatformRiskConfig(
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @Valid @RequestBody AdminPlatformRiskConfigUpdateRequest request) {
        log.info("trading.internal.risk-config.platform.update.received hedgeThresholdUsd={} adminUserId={} reason={}",
                request.hedgeThresholdUsd(), adminUserId, request.reason());
        bbookRiskAdminService.updatePlatformHedgeThreshold(request.hedgeThresholdUsd());
        return success(null);
    }

    @PostMapping("/risk-config/{symbol}/direction-imbalance")
    public ApiResponse<Void> updateDirectionImbalance(
            @PathVariable String symbol,
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @Valid @RequestBody AdminDirectionImbalanceUpdateRequest request) {
        log.info("trading.internal.risk-config.direction-imbalance.update.received symbol={} ratio={} minTotalUsd={} adminUserId={}",
                symbol, request.ratioThreshold(), request.minTotalUsd(), adminUserId);
        bbookRiskAdminService.updateDirectionImbalance(symbol, request.ratioThreshold(), request.minTotalUsd());
        return success(null);
    }

    @GetMapping("/user-risk-thresholds")
    public ApiResponse<AdminUserRiskThresholdListResponse> listUserRiskThresholds(
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return success(bbookRiskAdminService.listUserRiskThresholds(userId, safePage, safeSize));
    }

    @PostMapping("/user-risk-thresholds")
    public ApiResponse<AdminUserRiskThresholdItem> upsertUserRiskThreshold(
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @Valid @RequestBody AdminUserRiskThresholdUpsertRequest request) {
        log.info("trading.internal.user-risk-threshold.upsert.received userId={} adminUserId={} profitable={}",
                request.userId(), adminUserId, request.profitableUser());
        return success(bbookRiskAdminService.upsertUserRiskThreshold(
                request.userId(),
                request.netExposureThresholdUsd(),
                request.profitableNetExposureThresholdUsd(),
                request.profitableUser(),
                String.valueOf(adminUserId),
                request.reason()
        ));
    }

    @DeleteMapping("/user-risk-thresholds/{userId}")
    public ApiResponse<Void> deleteUserRiskThreshold(
            @PathVariable long userId,
            @RequestHeader("X-Admin-User-Id") long adminUserId) {
        log.info("trading.internal.user-risk-threshold.delete.received userId={} adminUserId={}", userId, adminUserId);
        bbookRiskAdminService.deleteUserRiskThreshold(userId);
        return success(null);
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
