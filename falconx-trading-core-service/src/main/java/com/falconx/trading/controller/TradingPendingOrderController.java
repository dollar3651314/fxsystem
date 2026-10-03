package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.application.TradingPendingOrderApplicationService;
import com.falconx.trading.dto.ModifyPendingOrderRequest;
import com.falconx.trading.dto.PlaceLimitOrderRequest;
import com.falconx.trading.dto.PlaceStopOrderRequest;
import com.falconx.trading.dto.TradingPendingOrderItemResponse;
import com.falconx.trading.dto.TradingPendingOrderListResponse;
import com.falconx.trading.entity.TradingOrderSide;
import com.falconx.trading.entity.TradingPendingOrderTrigger;
import com.falconx.trading.entity.TradingPendingOrderType;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-3-PENDING-ORDER：挂单 REST 入口。
 *
 * <p>由 gateway 转发，X-User-Id 经 gateway 注入；本控制器只读取 header 取 userId。
 */
@RestController
@RequestMapping("/api/v1/trading/orders")
public class TradingPendingOrderController {

    private static final Logger log = LoggerFactory.getLogger(TradingPendingOrderController.class);

    private final TradingPendingOrderApplicationService pendingOrderService;

    public TradingPendingOrderController(TradingPendingOrderApplicationService pendingOrderService) {
        this.pendingOrderService = pendingOrderService;
    }

    @PostMapping("/limit")
    public ApiResponse<TradingPendingOrderItemResponse> placeLimit(
            @RequestHeader("X-User-Id") long userId,
            @RequestHeader(value = "X-User-Group-Code", required = false, defaultValue = "default") String groupCode,
            @Valid @RequestBody PlaceLimitOrderRequest request) {
        log.info("trading.http.pending-order.limit.received userId={} symbol={} side={} qty={} limitPrice={} clientOrderId={}",
                userId, request.symbol(), request.side(), request.quantity(), request.limitPrice(), request.clientOrderId());
        TradingPendingOrderTrigger order = pendingOrderService.createOpening(
                userId, groupCode, request.symbol(), TradingOrderSide.valueOf(request.side()),
                TradingPendingOrderType.LIMIT, request.quantity(),
                request.limitPrice(), null,
                request.leverage(), request.marginMode(), request.clientOrderId());
        return success(toItem(order));
    }

    @PostMapping("/stop")
    public ApiResponse<TradingPendingOrderItemResponse> placeStop(
            @RequestHeader("X-User-Id") long userId,
            @RequestHeader(value = "X-User-Group-Code", required = false, defaultValue = "default") String groupCode,
            @Valid @RequestBody PlaceStopOrderRequest request) {
        log.info("trading.http.pending-order.stop.received userId={} symbol={} side={} qty={} stopPrice={} limitPrice={} clientOrderId={}",
                userId, request.symbol(), request.side(), request.quantity(),
                request.stopPrice(), request.limitPrice(), request.clientOrderId());
        TradingPendingOrderType type = request.limitPrice() == null
                ? TradingPendingOrderType.STOP
                : TradingPendingOrderType.STOP_LIMIT;
        TradingPendingOrderTrigger order = pendingOrderService.createOpening(
                userId, groupCode, request.symbol(), TradingOrderSide.valueOf(request.side()),
                type, request.quantity(),
                request.stopPrice(), request.limitPrice(),
                request.leverage(), request.marginMode(), request.clientOrderId());
        return success(toItem(order));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<TradingPendingOrderItemResponse> cancel(
            @RequestHeader("X-User-Id") long userId,
            @PathVariable("id") long id) {
        log.info("trading.http.pending-order.cancel.received userId={} id={}", userId, id);
        TradingPendingOrderTrigger order = pendingOrderService.cancel(userId, id, "USER_CANCELLED");
        return success(toItem(order));
    }

    @PatchMapping("/{id}")
    public ApiResponse<TradingPendingOrderItemResponse> modify(
            @RequestHeader("X-User-Id") long userId,
            @PathVariable("id") long id,
            @Valid @RequestBody ModifyPendingOrderRequest request) {
        log.info("trading.http.pending-order.modify.received userId={} id={} triggerPrice={} limitPrice={} qty={}",
                userId, id, request.triggerPrice(), request.limitPrice(), request.quantity());
        TradingPendingOrderTrigger order = pendingOrderService.modify(
                userId, id, request.triggerPrice(), request.limitPrice(), request.quantity());
        return success(toItem(order));
    }

    @GetMapping("/pending")
    public ApiResponse<TradingPendingOrderListResponse> listPending(
            @RequestHeader("X-User-Id") long userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        List<TradingPendingOrderTrigger> orders = pendingOrderService.listUserOpeningPending(userId, symbol, status, safePage, safeSize);
        long total = pendingOrderService.countUserOpeningPending(userId, symbol, status);
        return success(new TradingPendingOrderListResponse(safePage, safeSize, total,
                orders.stream().map(this::toItem).toList()));
    }

    private TradingPendingOrderItemResponse toItem(TradingPendingOrderTrigger order) {
        return new TradingPendingOrderItemResponse(
                order.id(),
                order.orderNo(),
                order.symbol(),
                order.orderType().name(),
                order.side().name(),
                order.quantity(),
                order.triggerPrice(),
                order.limitPrice(),
                order.leverage(),
                order.marginMode() == null ? null : order.marginMode().name(),
                order.frozenMargin(),
                order.frozenFee(),
                order.status().name(),
                order.parentPositionId(),
                order.triggerKind() == null ? null : order.triggerKind().name(),
                order.clientOrderId(),
                order.triggeredOrderId(),
                order.triggeredAt(),
                order.cancelledAt(),
                order.cancelReason(),
                order.createdAt(),
                order.updatedAt(),
                null  // 用户路径不下发 pricePrecision：客户端用 useSymbolPrecision 自带精度
        );
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
