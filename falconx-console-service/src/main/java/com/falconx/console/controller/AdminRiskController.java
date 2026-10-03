package com.falconx.console.controller;

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
import com.falconx.console.api.AdminUserRiskThresholdItem;
import com.falconx.console.api.AdminUserRiskThresholdListResponse;
import com.falconx.console.api.AdminUserRiskThresholdUpsertRequest;
import com.falconx.console.risk.AdminRiskApplicationService;
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
 * STAGE-2-RISK-ADMIN R9：风控管理 console REST。
 *
 * <p>路径与权限：详见 docs/api/管理端接口规范.md §8。
 * 所有 POST/PUT/DELETE 高危操作由 OperationAuditAspect 自动写 t_admin_operation_log。
 */
@RestController
@RequestMapping("/admin")
public class AdminRiskController {

    private static final Logger log = LoggerFactory.getLogger(AdminRiskController.class);

    private final AdminRiskApplicationService riskService;

    public AdminRiskController(AdminRiskApplicationService riskService) {
        this.riskService = riskService;
    }

    // ---- risk-actions ----

    @GetMapping("/risk-actions")
    @RequiresPermission(value = "risk-action:view", description = "查看风控动作列表")
    public ApiResponse<AdminRiskActionListResponse> listRiskActions(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) String triggerSource,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.risk-actions.list.received page={} size={}", page, size);
        return success(riskService.listRiskActions(symbol, actionType, triggerSource, isActive, page, size));
    }

    @PostMapping("/risk-actions")
    @RequiresPermission(value = "risk-action:activate", description = "激活风控动作（高危）")
    public ApiResponse<AdminRiskActionListResponse.Item> activateRiskAction(
            @Valid @RequestBody AdminRiskActionActivateRequest request) {
        log.info("admin.http.risk-actions.activate.received symbol={} actionType={}", request.symbol(), request.actionType());
        return success(riskService.activateRiskAction(request));
    }

    @PostMapping("/risk-actions/{id}/deactivate")
    @RequiresPermission(value = "risk-action:activate", description = "停用风控动作（高危）")
    public ApiResponse<AdminRiskActionListResponse.Item> deactivateRiskAction(
            @PathVariable long id,
            @Valid @RequestBody AdminRiskActionDeactivateRequest request) {
        log.info("admin.http.risk-actions.deactivate.received id={}", id);
        return success(riskService.deactivateRiskAction(id, request));
    }

    // ---- risk-configs ----

    @GetMapping("/risk-configs")
    @RequiresPermission(value = "risk-config:view", description = "查看 risk_config")
    public ApiResponse<AdminRiskConfigListResponse> listRiskConfigs(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String marketCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.risk-configs.list.received page={} size={}", page, size);
        return success(riskService.listRiskConfigs(symbol, marketCode, page, size));
    }

    @GetMapping("/risk-configs/{symbol}")
    @RequiresPermission(value = "risk-config:view", description = "查看 risk_config 单条")
    public ApiResponse<AdminRiskConfigListResponse.Item> getRiskConfig(@PathVariable String symbol) {
        log.info("admin.http.risk-configs.detail.received symbol={}", symbol);
        return success(riskService.getRiskConfig(symbol));
    }

    @PostMapping("/risk-configs")
    @RequiresPermission(value = "risk-config:update", description = "新建 risk_config（高危）")
    public ApiResponse<AdminRiskConfigListResponse.Item> createRiskConfig(
            @Valid @RequestBody AdminRiskConfigCreateRequest request) {
        log.info("admin.http.risk-configs.create.received symbol={}", request.symbol());
        return success(riskService.createRiskConfig(request));
    }

    @PutMapping("/risk-configs/{symbol}")
    @RequiresPermission(value = "risk-config:update", description = "编辑 risk_config（高危）")
    public ApiResponse<AdminRiskConfigListResponse.Item> updateRiskConfig(
            @PathVariable String symbol,
            @Valid @RequestBody AdminRiskConfigUpdateRequest request) {
        log.info("admin.http.risk-configs.update.received symbol={}", symbol);
        return success(riskService.updateRiskConfig(symbol, request));
    }

    @DeleteMapping("/risk-configs/{symbol}")
    @RequiresPermission(value = "risk-config:update", description = "删除 risk_config（高危）")
    public ApiResponse<Void> deleteRiskConfig(
            @PathVariable String symbol,
            @Valid @RequestBody AdminRiskConfigDeleteRequest request) {
        log.info("admin.http.risk-configs.delete.received symbol={}", symbol);
        riskService.deleteRiskConfig(symbol, request);
        return success(null);
    }

    // ---- risk-market-configs ----

    @GetMapping("/risk-market-configs")
    @RequiresPermission(value = "risk-config:view", description = "查看 risk_market_config")
    public ApiResponse<AdminRiskMarketConfigListResponse> listRiskMarketConfigs() {
        log.info("admin.http.risk-market-configs.list.received");
        return success(riskService.listRiskMarketConfigs());
    }

    @PutMapping("/risk-market-configs/{marketCode}")
    @RequiresPermission(value = "risk-market-config:update", description = "编辑 risk_market_config（高危）")
    public ApiResponse<AdminRiskMarketConfigListResponse.Item> updateRiskMarketConfig(
            @PathVariable String marketCode,
            @Valid @RequestBody AdminRiskMarketConfigUpdateRequest request) {
        log.info("admin.http.risk-market-configs.update.received marketCode={}", marketCode);
        return success(riskService.updateRiskMarketConfig(marketCode, request));
    }

    // ---- BBOOK-RISK-CONTROL-01：平台 / 方向集中度 / 用户级阈值 ----

    @PostMapping("/risk-config/platform")
    @RequiresPermission(value = "risk-config:update", description = "更新平台总敞口阈值（高危）")
    public ApiResponse<Void> updatePlatformRiskConfig(@Valid @RequestBody AdminPlatformRiskConfigUpdateRequest request) {
        log.info("admin.http.risk-config.platform.update.received hedgeThresholdUsd={}", request.hedgeThresholdUsd());
        riskService.updatePlatformRiskConfig(request);
        return success(null);
    }

    @PostMapping("/risk-config/{symbol}/direction-imbalance")
    @RequiresPermission(value = "risk-config:update", description = "更新方向集中度阈值（高危）")
    public ApiResponse<Void> updateDirectionImbalance(
            @PathVariable String symbol,
            @Valid @RequestBody AdminDirectionImbalanceUpdateRequest request) {
        log.info("admin.http.risk-config.direction-imbalance.update.received symbol={} ratio={}",
                symbol, request.ratioThreshold());
        riskService.updateDirectionImbalance(symbol, request);
        return success(null);
    }

    @GetMapping("/user-risk-thresholds")
    @RequiresPermission(value = "risk-config:view", description = "查看用户级风控阈值列表")
    public ApiResponse<AdminUserRiskThresholdListResponse> listUserRiskThresholds(
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.user-risk-thresholds.list.received userId={} page={} size={}", userId, page, size);
        return success(riskService.listUserRiskThresholds(userId, page, size));
    }

    @PostMapping("/user-risk-thresholds")
    @RequiresPermission(value = "risk-config:update", description = "新增/更新用户级风控阈值（高危）")
    public ApiResponse<AdminUserRiskThresholdItem> upsertUserRiskThreshold(
            @Valid @RequestBody AdminUserRiskThresholdUpsertRequest request) {
        log.info("admin.http.user-risk-thresholds.upsert.received userId={} profitable={}",
                request.userId(), request.profitableUser());
        return success(riskService.upsertUserRiskThreshold(request));
    }

    @DeleteMapping("/user-risk-thresholds/{userId}")
    @RequiresPermission(value = "risk-config:update", description = "删除用户级风控阈值（高危）")
    public ApiResponse<Void> deleteUserRiskThreshold(@PathVariable long userId) {
        log.info("admin.http.user-risk-thresholds.delete.received userId={}", userId);
        riskService.deleteUserRiskThreshold(userId);
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
