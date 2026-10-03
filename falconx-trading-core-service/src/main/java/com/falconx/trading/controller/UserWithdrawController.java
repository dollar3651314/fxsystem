package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.application.WithdrawCancelApplicationService;
import com.falconx.trading.application.WithdrawQueryApplicationService;
import com.falconx.trading.application.WithdrawSubmitApplicationService;
import com.falconx.trading.command.SubmitWithdrawCommand;
import com.falconx.trading.dto.WithdrawListResponse;
import com.falconx.trading.dto.WithdrawOrderResponse;
import com.falconx.trading.dto.WithdrawSubmitRequest;
import com.falconx.trading.entity.TradingWithdrawOrder;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-7-WITHDRAW Phase 1：客户端出金端点。
 *
 * <p>详细契约见 docs/api/REST接口规范.md §9.2。当前 commit 仅实现 POST 提交；
 * GET 列表 / 详情 / cancel 在后续 commit 补齐。
 */
@RestController
@RequestMapping("/api/v1/me/withdraw")
public class UserWithdrawController {

    private static final Logger log = LoggerFactory.getLogger(UserWithdrawController.class);

    private final WithdrawSubmitApplicationService submitApplicationService;
    private final WithdrawQueryApplicationService queryApplicationService;
    private final WithdrawCancelApplicationService cancelApplicationService;

    public UserWithdrawController(WithdrawSubmitApplicationService submitApplicationService,
                                   WithdrawQueryApplicationService queryApplicationService,
                                   WithdrawCancelApplicationService cancelApplicationService) {
        this.submitApplicationService = submitApplicationService;
        this.queryApplicationService = queryApplicationService;
        this.cancelApplicationService = cancelApplicationService;
    }

    @PostMapping
    public ApiResponse<WithdrawOrderResponse> submit(
            @RequestHeader("X-User-Id") long userId,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WithdrawSubmitRequest request) {
        log.info("trading.http.withdraw.submit.received userId={} network={} amount={} idempotencyKey={}",
                userId, request.network(), request.amount(), idempotencyKey);
        SubmitWithdrawCommand command = new SubmitWithdrawCommand(
                userId,
                request.amount(),
                request.currency(),
                request.network(),
                request.targetAddress(),
                request.whitelistId(),
                idempotencyKey
        );
        TradingWithdrawOrder order = submitApplicationService.submit(command);
        return success(toResponse(order));
    }

    @GetMapping
    public ApiResponse<WithdrawListResponse> list(
            @RequestHeader("X-User-Id") long userId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        List<TradingWithdrawOrder> orders = queryApplicationService.list(userId, status, safePage, safeSize);
        long total = queryApplicationService.count(userId, status);
        List<WithdrawOrderResponse> items = orders.stream()
                .map(UserWithdrawController::toResponse)
                .toList();
        return success(new WithdrawListResponse(safePage, safeSize, total, items));
    }

    @GetMapping("/{id}")
    public ApiResponse<WithdrawOrderResponse> getById(@RequestHeader("X-User-Id") long userId,
                                                       @PathVariable long id) {
        log.info("trading.http.withdraw.detail.received userId={} withdrawId={}", userId, id);
        TradingWithdrawOrder order = queryApplicationService.getOwnedByUserOrThrow(userId, id);
        return success(toResponse(order));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<WithdrawOrderResponse> cancel(@RequestHeader("X-User-Id") long userId,
                                                      @PathVariable long id) {
        log.info("trading.http.withdraw.cancel.received userId={} withdrawId={}", userId, id);
        TradingWithdrawOrder order = cancelApplicationService.cancel(userId, id);
        return success(toResponse(order));
    }

    static WithdrawOrderResponse toResponse(TradingWithdrawOrder order) {
        return new WithdrawOrderResponse(
                String.valueOf(order.id()),
                String.valueOf(order.userId()),
                order.amount(),
                order.currency(),
                order.network().name(),
                order.targetAddress(),
                order.status().name(),
                order.coolingUntil(),
                order.delayedUntil(),
                order.rejectReason(),
                order.txHash(),
                order.confirmations(),
                order.failureReason(),
                order.createdAt()
        );
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
