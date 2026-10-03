package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminReconciliationItem;
import com.falconx.console.api.AdminReconciliationListResponse;
import com.falconx.console.api.AdminReconciliationMarkResolvedRequest;
import com.falconx.console.api.AdminReconciliationMarkResolvedResponse;
import com.falconx.console.reconciliation.AdminReconciliationApplicationService;
import com.falconx.console.security.RequiresPermission;
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
 * STAGE-11-OBS-RECON §14：管理端入金对账 API。
 *
 * <p>路径：{@code /admin/reconciliation/deposits}
 *
 * <ul>
 *   <li>GET unmatched 列表：RBAC {@code reconciliation:view}，view 不写审计</li>
 *   <li>POST mark-resolved：RBAC {@code reconciliation:resolve}（高危），OperationAuditAspect 写
 *       {@code t_admin_operation_log} 含 target_type='reconciliation' + target_id={walletTxId} + risk_level=HIGH_RISK</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/reconciliation")
public class AdminReconciliationController {

    private static final Logger log = LoggerFactory.getLogger(AdminReconciliationController.class);

    private final AdminReconciliationApplicationService reconciliationService;

    public AdminReconciliationController(AdminReconciliationApplicationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/deposits/unmatched")
    @RequiresPermission(value = "reconciliation:view", description = "查看入金对账 unmatched 列表")
    public ApiResponse<AdminReconciliationListResponse> listUnmatched(
            @RequestParam(required = false) String chain,
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String discrepancyType,
            @RequestParam(required = false) OffsetDateTime fromDetectedAt,
            @RequestParam(required = false) OffsetDateTime toDetectedAt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.reconciliation.list.received chain={} token={} discrepancyType={} page={}",
                chain, token, discrepancyType, page);
        return success(reconciliationService.listUnmatched(
                chain, token, discrepancyType, fromDetectedAt, toDetectedAt, page, size));
    }

    @PostMapping("/deposits/{walletTxId}/mark-resolved")
    @RequiresPermission(value = "reconciliation:resolve", description = "手动标记入金对账项已 resolved（高危）")
    public ApiResponse<AdminReconciliationMarkResolvedResponse> markResolved(
            @PathVariable long walletTxId,
            @Valid @RequestBody AdminReconciliationMarkResolvedRequest request) {
        log.info("admin.http.reconciliation.mark-resolved.received walletTxId={} resolutionType={}",
                walletTxId, request.resolutionType());
        return success(reconciliationService.markResolved(walletTxId, request));
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
