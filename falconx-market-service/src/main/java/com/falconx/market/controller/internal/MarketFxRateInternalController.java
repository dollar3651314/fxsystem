package com.falconx.market.controller.internal;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.market.service.FxRateService;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Market FX rate internal RPC.
 *
 * <p>STAGE-14A Task 9. 消费者: trading-core-service, console-service.
 *
 * <p>暴露 FxRateService.snapshotAll 当前快照, 用于跨服务 fetch FX 实时汇率.
 */
@RestController
@RequestMapping("/internal/v1/market/fx")
public class MarketFxRateInternalController {

    private static final Logger log = LoggerFactory.getLogger(MarketFxRateInternalController.class);

    private final FxRateService service;

    public MarketFxRateInternalController(FxRateService service) {
        this.service = service;
    }

    /** 全量快照 */
    @GetMapping("/rates")
    public ApiResponse<List<FxRateSnapshotPayload>> snapshotAll() {
        log.info("market.internal.fx.rates.received");
        return success(service.snapshotAll());
    }

    /** 单对查询，大小写不敏感；未配置返回错误码 60010 */
    @GetMapping("/rates/{base}/{quote}")
    public ApiResponse<FxRateSnapshotPayload> single(
            @PathVariable String base,
            @PathVariable String quote) {
        log.info("market.internal.fx.rates.single.received base={} quote={}", base, quote);
        return service.snapshotAll().stream()
                .filter(p -> p.baseCurrency().equalsIgnoreCase(base)
                          && p.quoteCurrency().equalsIgnoreCase(quote))
                .findFirst()
                .map(MarketFxRateInternalController::success)
                .orElseGet(() -> new ApiResponse<>(
                        "60010",
                        "FX symbol 未配置: " + base + "/" + quote,
                        null,
                        OffsetDateTime.now(),
                        MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)));
    }

    private static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
