package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.application.WithdrawAdminApplicationService;
import com.falconx.trading.dto.WithdrawListResponse;
import com.falconx.trading.dto.WithdrawOrderResponse;
import com.falconx.trading.entity.TradingWithdrawOrder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * STAGE-7-WITHDRAW Phase 2：admin 审核 internal RPC（仅接受通过 gateway 注入 X-Internal-Token 的请求）。
 *
 * <p>路径前缀：{@code /internal/v1/trading/withdraws}。鉴权：{@link com.falconx.trading.security.TradingInternalApiTokenFilter}。
 *
 * <p>详细业务规则与状态机迁移见 docs/domain/状态机规范.md §7A。
 */
@RestController
@RequestMapping("/internal/v1/trading/withdraws")
public class AdminInternalTradingWithdrawController {

    private static final Logger log = LoggerFactory.getLogger(AdminInternalTradingWithdrawController.class);

    private final WithdrawAdminApplicationService applicationService;

    public AdminInternalTradingWithdrawController(WithdrawAdminApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping
    public ApiResponse<WithdrawListResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String network,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestHeader("X-Admin-User-Id") long adminUserId) {
        log.info("trading.admin.withdraw.list.received status={} userId={} network={} minAmount={} adminUserId={}",
                status, userId, network, minAmount, adminUserId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        List<WithdrawOrderResponse> items = applicationService
                .list(status, userId, network, minAmount, safePage, safeSize)
                .stream().map(AdminInternalTradingWithdrawController::toResponse)
                .toList();
        long total = applicationService.count(status, userId, network, minAmount);
        return success(new WithdrawListResponse(safePage, safeSize, total, items));
    }

    @GetMapping("/{id}")
    public ApiResponse<WithdrawOrderResponse> detail(@PathVariable long id,
                                                      @RequestHeader("X-Admin-User-Id") long adminUserId) {
        log.info("trading.admin.withdraw.detail.received id={} adminUserId={}", id, adminUserId);
        return success(toResponse(applicationService.detail(id)));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<WithdrawOrderResponse> approve(@PathVariable long id,
                                                       @RequestBody(required = false) ReviewRequest body,
                                                       @RequestHeader("X-Admin-User-Id") long adminUserId) {
        String note = body == null ? null : body.note();
        log.info("trading.admin.withdraw.approve.received id={} adminUserId={} hasNote={}",
                id, adminUserId, note != null);
        return success(toResponse(applicationService.approve(id, adminUserId, note)));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<WithdrawOrderResponse> reject(@PathVariable long id,
                                                      @RequestBody ReviewRequest body,
                                                      @RequestHeader("X-Admin-User-Id") long adminUserId) {
        log.info("trading.admin.withdraw.reject.received id={} adminUserId={}", id, adminUserId);
        return success(toResponse(applicationService.reject(id, adminUserId,
                body == null ? null : body.note())));
    }

    @PostMapping("/{id}/emergency-cancel")
    public ApiResponse<WithdrawOrderResponse> emergencyCancel(@PathVariable long id,
                                                               @RequestBody(required = false) ReviewRequest body,
                                                               @RequestHeader("X-Admin-User-Id") long adminUserId) {
        log.info("trading.admin.withdraw.emergency-cancel.received id={} adminUserId={}", id, adminUserId);
        return success(toResponse(applicationService.emergencyCancel(id, adminUserId,
                body == null ? null : body.note())));
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

    /**
     * approve / reject / emergency-cancel 通用请求体：
     * <ul>
     *   <li>approve: note 可选（审核备注）</li>
     *   <li>reject: note 必填（拒绝原因；空字符串视为缺失）</li>
     *   <li>emergency-cancel: note 可选（取消原因）</li>
     * </ul>
     */
    public record ReviewRequest(@Size(max = 512) String note) {}

    /** 暴露给 console 透传的请求体，明确 reject 时 note 必填。 */
    public record RejectRequest(@NotBlank @Size(max = 512) String note) {}
}
