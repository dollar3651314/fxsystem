package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.application.TradingPlatformConfigApplicationService;
import com.falconx.trading.application.TradingPlatformConfigApplicationService.PlatformConfigView;
import com.falconx.trading.command.UpdateCoolingPeriodCommand;
import com.falconx.trading.command.UpdateFxPauseBehaviorCommand;
import com.falconx.trading.command.UpdateRiskThresholdsCommand;
import com.falconx.trading.entity.FxPauseBehavior;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-14D3a Task 4：平台风控配置（冷静期 + StopOut/MarginCall 阈值）internal RPC。
 *
 * <p>路径前缀 {@code /internal/v1/trading/console/config}（master §7.6），经
 * {@code TradingInternalApiTokenFilter}（X-Internal-Token + X-Admin-User-Id）鉴权，
 * 由后续 D3b console 透传。参数范围校验在命令 record（Bean Validation），校验失败经
 * {@code TradingGlobalExceptionHandler} → {@code INVALID_REQUEST_PAYLOAD}。
 * @Transactional 与默认回退在 {@link TradingPlatformConfigApplicationService}。
 *
 * <ul>
 *   <li>{@code GET /platform-risk} 读冷静期 + 阈值（D3b 两页共用）</li>
 *   <li>{@code PUT /cooling-period} 写冷静期（60-604800）</li>
 *   <li>{@code PUT /risk-thresholds} 写 StopOut（0.05-0.95）/ MarginCall（0.50-2.00）</li>
 *   <li>{@code GET /fx-pause-behavior} 读 FX_PAUSED 类目行为开关全量（8 行）</li>
 *   <li>{@code PUT /fx-pause-behavior/{category}} 按类目（1-8）写开仓/平仓/强平开关</li>
 * </ul>
 *
 * <p>类上 {@code @Validated} 使 {@code @PathVariable @Min/@Max} 生效；越界 category 抛
 * {@code ConstraintViolationException} → {@code TradingGlobalExceptionHandler} → 400 INVALID_REQUEST_PAYLOAD(99004)。
 */
@RestController
@Validated
@RequestMapping("/internal/v1/trading/console/config")
public class AdminInternalTradingConfigController {

    private static final Logger log = LoggerFactory.getLogger(AdminInternalTradingConfigController.class);

    private final TradingPlatformConfigApplicationService configService;

    public AdminInternalTradingConfigController(TradingPlatformConfigApplicationService configService) {
        this.configService = configService;
    }

    @GetMapping("/platform-risk")
    public ApiResponse<PlatformConfigView> getPlatformConfig() {
        return success(configService.getPlatformConfig());
    }

    @PutMapping("/cooling-period")
    public ApiResponse<Void> updateCoolingPeriod(
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @Valid @RequestBody UpdateCoolingPeriodCommand command) {
        log.info("trading.internal.config.cooling-period.update.received seconds={} adminUserId={}",
                command.coolingPeriodSeconds(), adminUserId);
        configService.updateCoolingPeriod(command.coolingPeriodSeconds());
        return success(null);
    }

    @PutMapping("/risk-thresholds")
    public ApiResponse<Void> updateRiskThresholds(
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @Valid @RequestBody UpdateRiskThresholdsCommand command) {
        log.info("trading.internal.config.risk-thresholds.update.received stopOut={} marginCall={} adminUserId={}",
                command.stopOutLevel(), command.marginCallLevel(), adminUserId);
        configService.updateRiskThresholds(command.stopOutLevel(), command.marginCallLevel());
        return success(null);
    }

    @GetMapping("/fx-pause-behavior")
    public ApiResponse<List<FxPauseBehavior>> listFxPauseBehaviors() {
        return success(configService.listFxPauseBehaviors());
    }

    @PutMapping("/fx-pause-behavior/{category}")
    public ApiResponse<Void> updateFxPauseBehavior(
            @RequestHeader("X-Admin-User-Id") long adminUserId,
            @PathVariable @Min(1) @Max(8) int category,
            @Valid @RequestBody UpdateFxPauseBehaviorCommand command) {
        log.info("trading.internal.config.fx-pause.update.received category={} open={} close={} liq={} adminUserId={}",
                category, command.allowOpen(), command.allowClose(), command.allowLiquidation(), adminUserId);
        configService.updateFxPauseBehavior(category, command.allowOpen(), command.allowClose(),
                command.allowLiquidation(), adminUserId);
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
