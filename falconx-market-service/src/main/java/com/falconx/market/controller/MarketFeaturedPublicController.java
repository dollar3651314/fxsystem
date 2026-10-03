package com.falconx.market.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.market.service.MarketFeaturedSymbolService;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跑马灯热门产品公开读接口（客户端用）。
 *
 * <p>路径 {@code GET /api/v1/market/symbols/featured}，经 gateway 转发；
 * 返回 enabled=1 按 sort_order 升序的 symbol 列表，客户端 join 自身 /symbols
 * 元数据渲染跑马灯。空列表时客户端回退前端默认偏好。
 */
@RestController
@RequestMapping("/api/v1/market/symbols/featured")
public class MarketFeaturedPublicController {

    private final MarketFeaturedSymbolService service;

    public MarketFeaturedPublicController(MarketFeaturedSymbolService service) {
        this.service = service;
    }

    /** 客户端：启用项有序 symbol 列表。 */
    @GetMapping
    public ApiResponse<FeaturedSymbolsResponse> listFeatured() {
        List<String> symbols = service.listEnabledSymbols();
        return new ApiResponse<>(
                "0",
                "success",
                new FeaturedSymbolsResponse(symbols),
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }

    /** 跑马灯热门 symbol 列表响应。 */
    public record FeaturedSymbolsResponse(List<String> symbols) {
    }
}
