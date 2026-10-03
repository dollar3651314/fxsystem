package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.MarginModeConfigView;
import com.falconx.console.api.RiskThresholdView;
import com.falconx.console.api.UpdateCoolingPeriodRequest;
import com.falconx.console.api.UpdateRiskThresholdRequest;
import com.falconx.console.platformconfig.AdminPlatformConfigApplicationService;
import com.falconx.console.security.RequiresPermission;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-14D3b Task2：平台风控配置（冷静期 / StopOut & MarginCall 阈值）管理端 REST。
 *
 * <p>路径 {@code /admin/trading/{margin-mode-config|risk-thresholds}}，RBAC 权限点
 * {@code margin-mode-config:view|edit} / {@code risk-threshold:view|edit}。写操作（PUT）高危，
 * {@link AdminPlatformConfigApplicationService} 落审计快照，{@code OperationAuditAspect} 自动写
 * {@code t_admin_operation_log}。所有调用透传 trading-core internal RPC，console 不直写 trading 业务表。
 */
@RestController
@RequestMapping("/admin/trading")
public class AdminPlatformConfigController {

    private static final Logger log = LoggerFactory.getLogger(AdminPlatformConfigController.class);

    private final AdminPlatformConfigApplicationService platformConfigService;

    public AdminPlatformConfigController(AdminPlatformConfigApplicationService platformConfigService) {
        this.platformConfigService = platformConfigService;
    }

    @GetMapping("/margin-mode-config")
    @RequiresPermission(value = "margin-mode-config:view", description = "查看保证金模式冷静期配置")
    public ApiResponse<MarginModeConfigView> getMarginModeConfig() {
        log.info("admin.http.margin-mode-config.get.received");
        return success(platformConfigService.getMarginModeConfig());
    }

    @PutMapping("/margin-mode-config")
    @RequiresPermission(value = "margin-mode-config:edit", description = "编辑保证金模式冷静期配置（高危）")
    public ApiResponse<Void> updateMarginModeConfig(@Valid @RequestBody UpdateCoolingPeriodRequest request) {
        log.info("admin.http.margin-mode-config.put.received coolingPeriodSeconds={}",
                request.coolingPeriodSeconds());
        platformConfigService.updateCoolingPeriod(request);
        return success(null);
    }

    @GetMapping("/risk-thresholds")
    @RequiresPermission(value = "risk-threshold:view", description = "查看 StopOut/MarginCall 阈值配置")
    public ApiResponse<RiskThresholdView> getRiskThresholds() {
        log.info("admin.http.risk-thresholds.get.received");
        return success(platformConfigService.getRiskThresholds());
    }

    @PutMapping("/risk-thresholds")
    @RequiresPermission(value = "risk-threshold:edit", description = "编辑 StopOut/MarginCall 阈值配置（高危）")
    public ApiResponse<Void> updateRiskThresholds(@Valid @RequestBody UpdateRiskThresholdRequest request) {
        log.info("admin.http.risk-thresholds.put.received stopOutLevel={} marginCallLevel={}",
                request.stopOutLevel(), request.marginCallLevel());
        platformConfigService.updateRiskThresholds(request);
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
