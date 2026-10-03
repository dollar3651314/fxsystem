package com.falconx.market.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.market.entity.MarketFeaturedSymbol;
import com.falconx.market.service.MarketFeaturedSymbolService;
import com.falconx.market.service.MarketFeaturedSymbolService.FeaturedInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跑马灯热门产品 internal RPC controller（console-service 透传调用）。
 *
 * <p>路径前缀 {@code /internal/v1/market/symbols/featured}；filter 校验 X-Internal-Token。
 * GET 返回全部（含禁用，供管理端编辑）；PUT 全量替换（顺序即 sort_order）。
 */
@RestController
@RequestMapping("/internal/v1/market/symbols/featured")
public class MarketFeaturedSymbolInternalController {

    private static final Logger log = LoggerFactory.getLogger(MarketFeaturedSymbolInternalController.class);

    private final MarketFeaturedSymbolService service;

    public MarketFeaturedSymbolInternalController(MarketFeaturedSymbolService service) {
        this.service = service;
    }

    /** 管理端读：全部配置（含禁用），按 sort_order 升序。 */
    @GetMapping
    public ApiResponse<FeaturedListResponse> list() {
        log.info("market.internal.featured.list.received");
        List<FeaturedItem> items = service.listAll().stream()
                .map(it -> new FeaturedItem(it.platformSymbol(), it.sortOrder(), it.enabled() == 1))
                .toList();
        return success(new FeaturedListResponse(items));
    }

    /** 管理端写：全量替换（列表顺序即展示序）。 */
    @PutMapping
    public ApiResponse<FeaturedListResponse> replace(@Valid @RequestBody FeaturedReplaceRequest request) {
        log.info("market.internal.featured.replace.received count={}",
                request.items() == null ? 0 : request.items().size());
        List<FeaturedInput> inputs = (request.items() == null ? List.<FeaturedReplaceItem>of() : request.items())
                .stream()
                .map(it -> new FeaturedInput(it.symbol(), it.enabled() == null || it.enabled()))
                .toList();
        service.replaceAll(inputs);
        return list();
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

    /** 全量替换请求体。items 顺序即展示序。 */
    public record FeaturedReplaceRequest(
            @NotNull List<FeaturedReplaceItem> items
    ) {
    }

    /** 替换项：symbol + 是否启用（enabled 缺省视为 true）。 */
    public record FeaturedReplaceItem(String symbol, Boolean enabled) {
    }

    /** 列表响应。 */
    public record FeaturedListResponse(List<FeaturedItem> items) {
    }

    /** 列表项（含 sortOrder + enabled，供管理端渲染）。 */
    public record FeaturedItem(String symbol, int sortOrder, boolean enabled) {
    }
}
