package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.FxPauseBehaviorView;
import com.falconx.console.api.UpdateFxPauseBehaviorRequest;
import com.falconx.console.platformconfig.AdminFxPauseBehaviorApplicationService;
import com.falconx.console.security.RequiresPermission;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-14D3b Task3：FX_PAUSED 行为（按商品类目 1-8）管理端 REST。
 *
 * <p>路径 {@code /admin/trading/fx-pause-behavior}，RBAC 权限点
 * {@code fx:pause-behavior:view|edit}。写操作（PUT）高危，
 * {@link AdminFxPauseBehaviorApplicationService} 落审计快照，{@code OperationAuditAspect} 自动写
 * {@code t_admin_operation_log}。所有调用透传 trading-core internal RPC，console 不直写 trading 业务表。
 */
@RestController
@RequestMapping("/admin/trading")
public class AdminFxPauseBehaviorController {

    private static final Logger log = LoggerFactory.getLogger(AdminFxPauseBehaviorController.class);

    private final AdminFxPauseBehaviorApplicationService fxPauseBehaviorService;

    public AdminFxPauseBehaviorController(AdminFxPauseBehaviorApplicationService fxPauseBehaviorService) {
        this.fxPauseBehaviorService = fxPauseBehaviorService;
    }

    @GetMapping("/fx-pause-behavior")
    @RequiresPermission(value = "fx:pause-behavior:view", description = "查看 FX_PAUSED 行为配置（按类目）")
    public ApiResponse<List<FxPauseBehaviorView>> listBehaviors() {
        log.info("admin.http.fx-pause-behavior.get.received");
        return success(fxPauseBehaviorService.listBehaviors());
    }

    @PutMapping("/fx-pause-behavior/{category}")
    @RequiresPermission(value = "fx:pause-behavior:edit", description = "编辑 FX_PAUSED 行为配置（按类目，高危）")
    public ApiResponse<Void> updateBehavior(@PathVariable int category,
                                            @Valid @RequestBody UpdateFxPauseBehaviorRequest request) {
        log.info("admin.http.fx-pause-behavior.put.received category={} allowOpen={} allowClose={} allowLiquidation={}",
                category, request.allowOpen(), request.allowClose(), request.allowLiquidation());
        fxPauseBehaviorService.updateBehavior(category, request);
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
