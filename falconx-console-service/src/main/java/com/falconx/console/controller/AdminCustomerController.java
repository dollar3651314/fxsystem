package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminCustomerBalanceAdjustRequest;
import com.falconx.console.api.AdminCustomerBalanceAdjustResponse;
import com.falconx.console.api.AdminCustomerDetailResponse;
import com.falconx.console.api.AdminCustomerFreezeRequest;
import com.falconx.console.api.AdminCustomerFreezeResponse;
import com.falconx.console.api.AdminCustomerListResponse;
import com.falconx.console.api.AdminCustomerPatchRequest;
import com.falconx.console.customer.AdminCustomerApplicationService;
import com.falconx.console.security.RequiresPermission;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-2-CUSTOMER 客户管理 controller（5 个 REST 端点，按 docs/api/管理端接口规范.md §3）。
 *
 * <p>权限：
 *
 * <ul>
 *   <li>{@code customer:view} 列表 + 详情</li>
 *   <li>{@code customer:freeze} / {@code customer:unfreeze} / {@code customer:balance:adjust}（高风险）</li>
 * </ul>
 *
 * <p>所有写操作经 {@link com.falconx.console.security.OperationAuditAspect} 自动写
 * {@code t_admin_operation_log}（risk_level=HIGH_RISK）。
 */
@RestController
@RequestMapping("/admin/customers")
public class AdminCustomerController {

    private static final Logger log = LoggerFactory.getLogger(AdminCustomerController.class);

    private final AdminCustomerApplicationService adminCustomerApplicationService;

    public AdminCustomerController(AdminCustomerApplicationService adminCustomerApplicationService) {
        this.adminCustomerApplicationService = adminCustomerApplicationService;
    }

    @GetMapping
    @RequiresPermission(value = "customer:view", description = "查看客户列表")
    public ApiResponse<AdminCustomerListResponse> list(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.customers.list.received page={} size={}", page, size);
        List<String> statusNames = parseStatusList(status);
        AdminCustomerListResponse response = adminCustomerApplicationService.listCustomers(
                email, statusNames, from, to, page, size);
        return success(response);
    }

    @GetMapping("/{userId}")
    @RequiresPermission(value = "customer:view", description = "查看客户详情")
    public ApiResponse<AdminCustomerDetailResponse> detail(@PathVariable long userId) {
        log.info("admin.http.customers.detail.received userId={}", userId);
        return success(adminCustomerApplicationService.getCustomerDetail(userId));
    }

    /**
     * 管理端编辑客户详情（identity 元数据 + profile 资料），必填 reason ≥ 10 字符。
     * 高危：可改 status / kycLevel / email / 余额相关字段。审计 AOP 自动写 t_admin_operation_log。
     */
    @PatchMapping("/{userId}")
    @RequiresPermission(value = "customer:edit", description = "编辑客户详情（高风险）")
    public ApiResponse<Void> edit(@PathVariable long userId,
                                   @Valid @RequestBody AdminCustomerPatchRequest request) {
        log.info("admin.http.customers.edit.received userId={} identityKeys={} profileKeys={}",
                userId,
                request.identity() == null ? 0 : 1,
                request.profile() == null ? 0 : 1);
        adminCustomerApplicationService.editCustomer(userId, request);
        return success(null);
    }

    @PostMapping("/{userId}/freeze")
    @RequiresPermission(value = "customer:freeze", description = "冻结客户账户（高风险）")
    public ApiResponse<AdminCustomerFreezeResponse> freeze(@PathVariable long userId,
                                                           @Valid @RequestBody AdminCustomerFreezeRequest request) {
        log.info("admin.http.customers.freeze.received userId={}", userId);
        return success(adminCustomerApplicationService.freezeCustomer(userId, request.reason()));
    }

    @PostMapping("/{userId}/unfreeze")
    @RequiresPermission(value = "customer:unfreeze", description = "解冻客户账户（高风险）")
    public ApiResponse<AdminCustomerFreezeResponse> unfreeze(@PathVariable long userId,
                                                             @Valid @RequestBody AdminCustomerFreezeRequest request) {
        log.info("admin.http.customers.unfreeze.received userId={}", userId);
        return success(adminCustomerApplicationService.unfreezeCustomer(userId, request.reason()));
    }

    @PostMapping("/{userId}/balance/adjust")
    @RequiresPermission(value = "customer:balance:adjust", description = "调整客户余额（高风险）")
    public ApiResponse<AdminCustomerBalanceAdjustResponse> adjustBalance(
            @PathVariable long userId,
            @Valid @RequestBody AdminCustomerBalanceAdjustRequest request) {
        log.info("admin.http.customers.balance-adjust.received userId={} delta={}", userId, request.deltaUSD());
        return success(adminCustomerApplicationService.adjustBalance(userId, request.deltaUSD(), request.reason()));
    }

    private List<String> parseStatusList(String status) {
        if (status == null || status.isBlank()) return List.of();
        return java.util.Arrays.stream(status.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
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
