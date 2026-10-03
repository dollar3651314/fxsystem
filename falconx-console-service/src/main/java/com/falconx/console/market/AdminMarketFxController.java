package com.falconx.console.market;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.security.RequiresPermission;
import com.falconx.infrastructure.trace.TraceIdConstants;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-14E2 Task3：管理端 FX 实时汇率监控 REST。
 *
 * <p>路径 {@code /admin/market/fx/rates}，RBAC 权限点 {@code fx:view}（只读，非高危，
 * 不入 HighRiskPermissionRegistry）。透传 market-service 14A internal RPC
 * {@code /internal/v1/market/fx/rates}（console 不直写 market 业务表）。
 * 给 admin FX 监控页（Task4 前端）提供全量 8 FX rate 快照数据。
 */
@RestController
@RequestMapping("/admin/market")
public class AdminMarketFxController {

    private static final Logger log = LoggerFactory.getLogger(AdminMarketFxController.class);

    private final AdminMarketFxApplicationService marketFxService;

    public AdminMarketFxController(AdminMarketFxApplicationService marketFxService) {
        this.marketFxService = marketFxService;
    }

    @GetMapping("/fx/rates")
    @RequiresPermission(value = "fx:view", description = "查看 FX 实时汇率监控")
    public ApiResponse<List<FxRateView>> listFxRates() {
        log.info("admin.http.market.fx.rates.get.received");
        return success(marketFxService.listFxRates());
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
