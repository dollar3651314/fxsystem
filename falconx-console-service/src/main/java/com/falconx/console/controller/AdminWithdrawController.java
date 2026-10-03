package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminWithdrawApproveRequest;
import com.falconx.console.api.AdminWithdrawEmergencyCancelRequest;
import com.falconx.console.api.AdminWithdrawItem;
import com.falconx.console.api.AdminWithdrawListResponse;
import com.falconx.console.api.AdminWithdrawRejectRequest;
import com.falconx.console.security.RequiresPermission;
import com.falconx.console.withdraw.AdminWithdrawApplicationService;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-7-WITHDRAW Phase 4：管理端出金审核 controller。路径 {@code /admin/withdraws}。
 *
 * <p>透传到 trading-core {@code /internal/v1/trading/withdraws} 5 端点；RBAC 与审计 AOP 由
 * {@link RequiresPermission} 触发：view 端点（list / detail）不写审计，review / emergency-cancel
 * 写 {@code t_admin_operation_log}，risk_level 由 {@link com.falconx.console.security.HighRiskPermissionRegistry} 决定
 * （review + emergency-cancel 均为 HIGH_RISK）。
 *
 * <p>错误码翻译表：见 [管理端接口规范 §10.6](docs/api/管理端接口规范.md#106-阶段-7-错误码块90500-90599)。
 */
@RestController
@RequestMapping("/admin/withdraws")
public class AdminWithdrawController {

    private static final Logger log = LoggerFactory.getLogger(AdminWithdrawController.class);

    private final AdminWithdrawApplicationService withdrawService;

    public AdminWithdrawController(AdminWithdrawApplicationService withdrawService) {
        this.withdrawService = withdrawService;
    }

    @GetMapping
    @RequiresPermission(value = "withdraw:view", description = "查看出金审核列表")
    public ApiResponse<AdminWithdrawListResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String network,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        log.info("admin.http.withdraw.list.received status={} userId={} network={}",
                status, userId, network);
        return success(withdrawService.list(status, userId, network, minAmount, maxAmount, page, pageSize));
    }

    @GetMapping("/{id}")
    @RequiresPermission(value = "withdraw:view", description = "查看出金详情")
    public ApiResponse<AdminWithdrawItem> detail(@PathVariable long id) {
        log.info("admin.http.withdraw.detail.received id={}", id);
        return success(withdrawService.detail(id));
    }

    @PostMapping("/{id}/approve")
    @RequiresPermission(value = "withdraw:review", description = "通过出金（高危）")
    public ApiResponse<AdminWithdrawItem> approve(@PathVariable long id,
                                                   @Valid @RequestBody(required = false) AdminWithdrawApproveRequest request) {
        String reviewNote = request == null ? null : request.reviewNote();
        log.info("admin.http.withdraw.approve.received id={} hasNote={}", id, reviewNote != null);
        return success(withdrawService.approve(id, reviewNote));
    }

    @PostMapping("/{id}/reject")
    @RequiresPermission(value = "withdraw:review", description = "拒绝出金（高危）")
    public ApiResponse<AdminWithdrawItem> reject(@PathVariable long id,
                                                  @Valid @RequestBody AdminWithdrawRejectRequest request) {
        log.info("admin.http.withdraw.reject.received id={}", id);
        return success(withdrawService.reject(id, request.reason()));
    }

    @PostMapping("/{id}/emergency-cancel")
    @RequiresPermission(value = "withdraw:emergency-cancel", description = "APPROVED_DELAYED 期紧急取消（最高危）")
    public ApiResponse<AdminWithdrawItem> emergencyCancel(@PathVariable long id,
                                                           @Valid @RequestBody AdminWithdrawEmergencyCancelRequest request) {
        log.info("admin.http.withdraw.emergency-cancel.received id={}", id);
        return success(withdrawService.emergencyCancel(id, request.reason()));
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
