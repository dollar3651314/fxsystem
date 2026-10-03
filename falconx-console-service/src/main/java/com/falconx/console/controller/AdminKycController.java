package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminKycDetailResponse;
import com.falconx.console.api.AdminKycItem;
import com.falconx.console.api.AdminKycListResponse;
import com.falconx.console.api.AdminKycRejectRequest;
import com.falconx.console.kyc.AdminKycApplicationService;
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
 * STAGE-6-KYC Phase 2：KYC 审核 controller。路径 {@code /admin/kyc}。
 */
@RestController
@RequestMapping("/admin/kyc")
public class AdminKycController {

    private static final Logger log = LoggerFactory.getLogger(AdminKycController.class);

    private final AdminKycApplicationService kycService;

    public AdminKycController(AdminKycApplicationService kycService) {
        this.kycService = kycService;
    }

    @GetMapping
    @RequiresPermission(value = "kyc:view", description = "查看 KYC 审核列表")
    public ApiResponse<AdminKycListResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("admin.http.kyc.list.received status={} userId={}", status, userId);
        return success(kycService.list(status, userId, page, size));
    }

    @GetMapping("/{submissionId}")
    @RequiresPermission(value = "kyc:view", description = "查看 KYC 详情（含证件图片）")
    public ApiResponse<AdminKycDetailResponse> detail(@PathVariable long submissionId) {
        log.info("admin.http.kyc.detail.received submissionId={}", submissionId);
        return success(kycService.detail(submissionId));
    }

    @PostMapping("/{submissionId}/approve")
    @RequiresPermission(value = "kyc:review", description = "通过 KYC（高危）")
    public ApiResponse<AdminKycItem> approve(@PathVariable long submissionId) {
        log.info("admin.http.kyc.approve.received submissionId={}", submissionId);
        return success(kycService.approve(submissionId));
    }

    @PostMapping("/{submissionId}/reject")
    @RequiresPermission(value = "kyc:review", description = "拒绝 KYC（高危）")
    public ApiResponse<AdminKycItem> reject(@PathVariable long submissionId,
                                             @Valid @RequestBody AdminKycRejectRequest request) {
        log.info("admin.http.kyc.reject.received submissionId={} reason={}", submissionId, request.reason());
        return success(kycService.reject(submissionId, request.reason()));
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
