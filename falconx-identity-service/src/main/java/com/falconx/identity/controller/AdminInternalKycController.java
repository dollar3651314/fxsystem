package com.falconx.identity.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.identity.application.IdentityKycApplicationService;
import com.falconx.identity.entity.KycDocument;
import com.falconx.identity.entity.KycSubmission;
import com.falconx.identity.entity.KycSubmissionStatus;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.validation.constraints.NotBlank;
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
 * STAGE-6-KYC Phase 1：KYC 审核 admin internal RPC。
 *
 * <p>路径：{@code /internal/v1/identity/kyc}。
 */
@RestController
@RequestMapping("/internal/v1/identity/kyc")
public class AdminInternalKycController {

    private static final Logger log = LoggerFactory.getLogger(AdminInternalKycController.class);

    private final IdentityKycApplicationService kycService;

    public AdminInternalKycController(IdentityKycApplicationService kycService) {
        this.kycService = kycService;
    }

    @GetMapping
    public ApiResponse<AdminKycListResponse> list(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) Long userId,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        KycSubmissionStatus statusEnum = status == null || status.isBlank() ? null : KycSubmissionStatus.valueOf(status);
        List<AdminKycItem> items = kycService.listForAdmin(statusEnum, userId, page, size).stream()
                .map(AdminInternalKycController::toItem).toList();
        long total = kycService.countForAdmin(statusEnum, userId);
        return success(new AdminKycListResponse(Math.max(1, page), Math.min(Math.max(1, size), 100), total, items));
    }

    @GetMapping("/{submissionId}")
    public ApiResponse<AdminKycDetailResponse> detail(@PathVariable long submissionId) {
        KycSubmission submission = kycService.detailForAdmin(submissionId);
        List<KycDocument> documents = kycService.documentsForAdmin(submissionId);
        return success(new AdminKycDetailResponse(
                toItem(submission),
                documents.stream().map(d -> new AdminKycDocumentItem(
                        String.valueOf(d.id()),
                        d.docType().name(),
                        d.mimeType(),
                        d.dataBase64(),
                        d.sha256()
                )).toList()
        ));
    }

    @PostMapping("/{submissionId}/approve")
    public ApiResponse<AdminKycItem> approve(@PathVariable long submissionId,
                                              @RequestHeader("X-Admin-User-Id") long adminUserId,
                                              @RequestBody(required = false) Map<String, String> body) {
        log.info("identity.internal.kyc.approve.received submissionId={} adminUserId={}", submissionId, adminUserId);
        return success(toItem(kycService.approve(submissionId, adminUserId)));
    }

    @PostMapping("/{submissionId}/reject")
    public ApiResponse<AdminKycItem> reject(@PathVariable long submissionId,
                                             @RequestHeader("X-Admin-User-Id") long adminUserId,
                                             @RequestBody RejectRequest request) {
        log.info("identity.internal.kyc.reject.received submissionId={} adminUserId={} reason={}",
                submissionId, adminUserId, request.reason());
        return success(toItem(kycService.reject(submissionId, adminUserId, request.reason())));
    }

    private static AdminKycItem toItem(KycSubmission s) {
        return new AdminKycItem(
                String.valueOf(s.id()),
                String.valueOf(s.userId()),
                s.level(),
                s.status().name(),
                s.idType().name(),
                s.idNumber(),
                s.submittedAt(),
                s.reviewerId() == null ? null : String.valueOf(s.reviewerId()),
                s.reviewAt(),
                s.rejectReason()
        );
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }

    public record RejectRequest(@NotBlank String reason) {}

    public record AdminKycListResponse(int page, int pageSize, long total, List<AdminKycItem> items) {}

    public record AdminKycItem(
            String submissionId,
            String userId,
            int level,
            String status,
            String idType,
            String idNumber,
            OffsetDateTime submittedAt,
            String reviewerId,
            OffsetDateTime reviewAt,
            String rejectReason
    ) {}

    public record AdminKycDetailResponse(AdminKycItem submission, List<AdminKycDocumentItem> documents) {}

    public record AdminKycDocumentItem(String id, String docType, String mimeType, String dataBase64, String sha256) {}
}
