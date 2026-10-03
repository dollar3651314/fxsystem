package com.falconx.identity.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.identity.application.IdentityKycApplicationService;
import com.falconx.identity.entity.KycDocumentType;
import com.falconx.identity.entity.KycIdType;
import com.falconx.identity.entity.KycSubmission;
import com.falconx.identity.repository.IdentityUserRepository;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-6-KYC Phase 1：用户 KYC 提交 / 状态查询。
 *
 * <p>路径：{@code /api/v1/me/kyc}。X-User-Id 由 gateway 鉴权后透传。
 */
@RestController
@RequestMapping("/api/v1/me/kyc")
public class UserKycController {

    private static final Logger log = LoggerFactory.getLogger(UserKycController.class);

    private final IdentityKycApplicationService kycService;
    private final IdentityUserRepository identityUserRepository;

    public UserKycController(IdentityKycApplicationService kycService,
                              IdentityUserRepository identityUserRepository) {
        this.kycService = kycService;
        this.identityUserRepository = identityUserRepository;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<KycSubmissionResponse>> submit(
            @RequestHeader("X-User-Id") long userId,
            @Valid @RequestBody SubmitKycRequest request) {
        log.info("identity.http.kyc.submit.received userId={} idType={}", userId, request.idType());
        Map<KycDocumentType, IdentityKycApplicationService.DocumentPayload> docs = new HashMap<>();
        if (request.idFrontBase64() != null && !request.idFrontBase64().isBlank()) {
            docs.put(KycDocumentType.ID_FRONT,
                    new IdentityKycApplicationService.DocumentPayload(request.idFrontBase64(), request.idFrontMimeType()));
        }
        if (request.idBackBase64() != null && !request.idBackBase64().isBlank()) {
            docs.put(KycDocumentType.ID_BACK,
                    new IdentityKycApplicationService.DocumentPayload(request.idBackBase64(), request.idBackMimeType()));
        }
        if (request.selfieBase64() != null && !request.selfieBase64().isBlank()) {
            docs.put(KycDocumentType.HOLDING_SELFIE,
                    new IdentityKycApplicationService.DocumentPayload(request.selfieBase64(), request.selfieMimeType()));
        }
        KycSubmission submission = kycService.submit(userId, KycIdType.valueOf(request.idType()), request.idNumber(), docs);
        int kycLevel = identityUserRepository.findKycLevelByUserId(userId).orElse(0);
        return ResponseEntity.ok(success(toResponse(kycLevel, submission, userId)));
    }

    /**
     * 用户 KYC 综合状态：currentKycLevel（t_user.kyc_level）+ 最新 submission。
     *
     * <p>kyc_level 是权限源，submission 是审核工作流状态。两者可能不一致：
     * admin 在客户编辑里直接 patch kyc_level=1 时，旧 PENDING submission 留下来，
     * 这时前端应以 currentKycLevel ≥ 1 为「已认证」标志，submission 仅作工作流参考。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<KycSubmissionResponse>> getLatest(@RequestHeader("X-User-Id") long userId) {
        int currentKycLevel = identityUserRepository.findKycLevelByUserId(userId).orElse(0);
        KycSubmission latest = kycService.getLatestStatus(userId).orElse(null);
        return ResponseEntity.ok(success(toResponse(currentKycLevel, latest, userId)));
    }

    private static KycSubmissionResponse toResponse(int currentKycLevel, KycSubmission s, long userId) {
        if (s == null) {
            // 无 submission，但仍要把 currentKycLevel 透出（admin 可能直接 patch kyc_level=1 而无 submission）
            return new KycSubmissionResponse(
                    currentKycLevel, null, String.valueOf(userId),
                    0, null, null, null, null, null, null
            );
        }
        return new KycSubmissionResponse(
                currentKycLevel,
                String.valueOf(s.id()),
                String.valueOf(s.userId()),
                s.level(),
                s.status().name(),
                s.idType().name(),
                s.idNumber(),
                s.submittedAt(),
                s.reviewAt(),
                s.rejectReason()
        );
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }

    public record SubmitKycRequest(
            @NotBlank String idType,
            @NotBlank String idNumber,
            @NotBlank String idFrontBase64,
            String idFrontMimeType,
            @NotBlank String idBackBase64,
            String idBackMimeType,
            @NotBlank String selfieBase64,
            String selfieMimeType
    ) {}

    /**
     * @param currentKycLevel t_user.kyc_level 当前值（权限源；前端用于显示 "已认证"）
     * @param submissionId    最新 submission ID（用户从未提交时为 null）
     * @param status          submission 审核状态 PENDING/APPROVED/REJECTED（无 submission 时 null）
     */
    public record KycSubmissionResponse(
            int currentKycLevel,
            String submissionId,
            String userId,
            int level,
            String status,
            String idType,
            String idNumber,
            OffsetDateTime submittedAt,
            OffsetDateTime reviewAt,
            String rejectReason
    ) {}
}
