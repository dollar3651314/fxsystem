package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.application.TradingPriceAlertApplicationService;
import com.falconx.trading.dto.CreatePriceAlertRequest;
import com.falconx.trading.dto.PriceAlertItemResponse;
import com.falconx.trading.dto.PriceAlertListResponse;
import com.falconx.trading.entity.TradingPriceAlert;
import com.falconx.trading.entity.TradingPriceAlertDirection;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-4-PRICE-ALERT：价格告警 REST。
 */
@RestController
@RequestMapping("/api/v1/trading/price-alerts")
public class TradingPriceAlertController {

    private static final Logger log = LoggerFactory.getLogger(TradingPriceAlertController.class);
    private static final int MAX_TRIGGER_COUNT = 3;

    private final TradingPriceAlertApplicationService priceAlertService;

    public TradingPriceAlertController(TradingPriceAlertApplicationService priceAlertService) {
        this.priceAlertService = priceAlertService;
    }

    @PostMapping
    public ApiResponse<PriceAlertItemResponse> create(
            @RequestHeader("X-User-Id") long userId,
            @Valid @RequestBody CreatePriceAlertRequest request) {
        TradingPriceAlertDirection direction = request.direction() == null || request.direction().isBlank()
                ? null
                : TradingPriceAlertDirection.valueOf(request.direction());
        log.info("trading.http.price-alert.create.received userId={} symbol={} direction={} target={}",
                userId, request.symbol(), direction, request.targetPrice());
        TradingPriceAlert alert = priceAlertService.create(userId, request.symbol(),
                direction, request.targetPrice(), request.note());
        return success(toItem(alert));
    }

    @GetMapping
    public ApiResponse<PriceAlertListResponse> list(
            @RequestHeader("X-User-Id") long userId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        List<TradingPriceAlert> items = priceAlertService.list(userId, status, symbol, safePage, safeSize);
        long total = priceAlertService.count(userId, status, symbol);
        return success(new PriceAlertListResponse(safePage, safeSize, total,
                items.stream().map(this::toItem).toList()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<PriceAlertItemResponse> cancel(
            @RequestHeader("X-User-Id") long userId,
            @PathVariable long id) {
        log.info("trading.http.price-alert.cancel.received userId={} id={}", userId, id);
        TradingPriceAlert alert = priceAlertService.cancel(userId, id);
        return success(toItem(alert));
    }

    private PriceAlertItemResponse toItem(TradingPriceAlert a) {
        return new PriceAlertItemResponse(
                String.valueOf(a.id()),
                null, // 用户路径不暴露 userId（用户看自己不需要），admin 路径在 AdminInternalTradingConsoleController 单独填入
                a.symbol(),
                a.direction().name(),
                a.targetPrice(),
                a.status().name(),
                a.note(),
                a.basePrice(),
                a.triggerCount(),
                Math.max(0, MAX_TRIGGER_COUNT - a.triggerCount()),
                a.lastTriggeredAt(),
                a.lastTriggeredPrice(),
                a.cancelledAt(),
                a.cancelSource(),
                a.createdAt(),
                a.updatedAt(),
                null  // 用户路径不下发 pricePrecision：客户端用 useSymbolPrecision 自带精度
        );
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
