package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminWalletProvisionDlqItem;
import com.falconx.console.api.AdminWalletProvisionDlqListResponse;
import com.falconx.console.api.AdminWalletProvisionRetryRequest;
import com.falconx.console.security.RequiresPermission;
import com.falconx.console.wallet.AdminWalletProvisionApplicationService;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.validation.Valid;
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
 * STAGE-5-WALLET-PROVISION Phase 2：地址预分配 DLQ 管理端 controller。
 *
 * <p>路径：{@code /admin/wallet/provision-dlq}。
 */
@RestController
@RequestMapping("/admin/wallet/provision-dlq")
public class AdminWalletProvisionController {

    private static final Logger log = LoggerFactory.getLogger(AdminWalletProvisionController.class);

    private final AdminWalletProvisionApplicationService adminService;

    public AdminWalletProvisionController(AdminWalletProvisionApplicationService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    @RequiresPermission(value = "wallet-provision:view", description = "查看地址预分配死信队列")
    public ApiResponse<AdminWalletProvisionDlqListResponse> list(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.wallet.provision-dlq.list.received status={} userId={} page={}", status, userId, page);
        return success(adminService.list(status, userId, page, size));
    }

    @PostMapping("/{id}/retry")
    @RequiresPermission(value = "wallet-provision:retry", description = "重试地址预分配（高危）")
    public ApiResponse<AdminWalletProvisionDlqItem> retry(
            @PathVariable long id,
            @Valid @RequestBody AdminWalletProvisionRetryRequest request) {
        log.info("admin.http.wallet.provision-dlq.retry.received id={} reason={}", id, request.reason());
        return success(adminService.retry(id, request.reason()));
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
