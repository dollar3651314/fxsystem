package com.falconx.wallet.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.wallet.application.WalletAddressProvisionAdminApplicationService;
import com.falconx.wallet.entity.WalletAddressProvisionDlqEntry;
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
 * STAGE-5-WALLET-PROVISION Phase 2：地址预分配 DLQ admin internal RPC。
 *
 * <p>路径前缀：{@code /internal/v1/wallet/console/provision-dlq}。
 * 鉴权：{@link com.falconx.wallet.security.WalletInternalApiTokenFilter} 校验
 * X-Internal-Token + X-Admin-User-Id。
 */
@RestController
@RequestMapping("/internal/v1/wallet/console/provision-dlq")
public class AdminInternalWalletProvisionController {

    private static final Logger log = LoggerFactory.getLogger(AdminInternalWalletProvisionController.class);

    private final WalletAddressProvisionAdminApplicationService adminService;

    public AdminInternalWalletProvisionController(WalletAddressProvisionAdminApplicationService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    public ApiResponse<DlqListResponse> list(@RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) Long userId,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        log.info("wallet.admin.provision-dlq.list.received status={} userId={} page={} size={}",
                status, userId, page, size);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        List<DlqItemResponse> items = adminService.list(status, userId, safePage, safeSize).stream()
                .map(AdminInternalWalletProvisionController::toItem)
                .toList();
        long total = adminService.count(status, userId);
        return success(new DlqListResponse(safePage, safeSize, total, items));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<DlqItemResponse> retry(@PathVariable long id,
                                               @RequestHeader("X-Admin-User-Id") long adminUserId,
                                               @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        log.info("wallet.admin.provision-dlq.retry.received id={} adminUserId={} reason={}",
                id, adminUserId, reason);
        WalletAddressProvisionDlqEntry resolved = adminService.retry(id, adminUserId, reason);
        return success(toItem(resolved));
    }

    private static DlqItemResponse toItem(WalletAddressProvisionDlqEntry e) {
        return new DlqItemResponse(
                String.valueOf(e.id()),
                e.eventId(),
                String.valueOf(e.userId()),
                e.uid(),
                e.email(),
                e.attemptCount(),
                e.status().name(),
                e.lastErrorCode(),
                e.lastErrorMessage(),
                e.lastAttemptAt(),
                e.resolvedAt(),
                e.createdAt()
        );
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }

    public record DlqListResponse(int page, int pageSize, long total, List<DlqItemResponse> items) {}

    public record DlqItemResponse(
            String id,
            String eventId,
            String userId,
            String uid,
            String email,
            int attemptCount,
            String status,
            String lastErrorCode,
            String lastErrorMessage,
            OffsetDateTime lastAttemptAt,
            OffsetDateTime resolvedAt,
            OffsetDateTime createdAt
    ) {}
}
