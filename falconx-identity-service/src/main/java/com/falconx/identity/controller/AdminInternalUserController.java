package com.falconx.identity.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.identity.api.AdminFreezeRequest;
import com.falconx.identity.api.AdminFreezeResponse;
import com.falconx.identity.api.AdminProfilePatchRequest;
import com.falconx.identity.api.AdminUserPatchRequest;
import com.falconx.identity.api.KycStatusResponse;
import com.falconx.identity.application.IdentityUserAdminApplicationService;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-2-CUSTOMER：identity-service 内部 RPC（仅接受来自 console-service 通过 gateway 的调用）。
 *
 * <p>路径：{@code /internal/v1/identity/users/{userId}/{action}}
 *
 * <p>鉴权：{@link com.falconx.identity.security.IdentityInternalApiTokenFilter} 校验
 * {@code X-Internal-Token} + {@code X-Admin-User-Id}（已过 filter 时 header 必然存在）。
 *
 * <p>详细契约见 [`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §4.1 §4.2。
 */
@RestController
@RequestMapping("/internal/v1/identity/users")
public class AdminInternalUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminInternalUserController.class);

    private final IdentityUserAdminApplicationService userAdminApplicationService;

    public AdminInternalUserController(IdentityUserAdminApplicationService userAdminApplicationService) {
        this.userAdminApplicationService = userAdminApplicationService;
    }

    @PostMapping("/{userId}/freeze")
    public ApiResponse<AdminFreezeResponse> freeze(@PathVariable long userId,
                                                   @RequestHeader("X-Admin-User-Id") long adminUserId,
                                                   @Valid @RequestBody AdminFreezeRequest request) {
        log.info("identity.internal.freeze.received userId={} adminUserId={}", userId, adminUserId);
        AdminFreezeResponse response = userAdminApplicationService.freeze(userId, adminUserId, request.reason());
        return success(response);
    }

    @PostMapping("/{userId}/unfreeze")
    public ApiResponse<AdminFreezeResponse> unfreeze(@PathVariable long userId,
                                                     @RequestHeader("X-Admin-User-Id") long adminUserId,
                                                     @Valid @RequestBody AdminFreezeRequest request) {
        log.info("identity.internal.unfreeze.received userId={} adminUserId={}", userId, adminUserId);
        AdminFreezeResponse response = userAdminApplicationService.unfreeze(userId, adminUserId, request.reason());
        return success(response);
    }

    /**
     * STAGE-7-WITHDRAW：trading-core 出金前置查询用户 KYC 等级。
     *
     * <p>调用方：trading-core POST /api/v1/me/withdraw。filter 已强制 X-Admin-User-Id
     * 存在，service-to-service 调用方传服务标识值即可（不一定是真实 admin）。
     */
    /**
     * 管理端 patch 用户元数据（email / emailVerified / groupCode / kycLevel / status）。
     * 所有字段允许 null（COALESCE 保留原值）。
     */
    @PatchMapping("/{userId}")
    public ApiResponse<Void> updateUser(@PathVariable long userId,
                                         @RequestHeader("X-Admin-User-Id") long adminUserId,
                                         @Valid @RequestBody AdminUserPatchRequest request) {
        log.info("identity.internal.user-patch.received userId={} adminUserId={}", userId, adminUserId);
        userAdminApplicationService.updateUserByAdmin(userId, adminUserId, request);
        return success(null);
    }

    /**
     * 管理端 patch 用户基础资料（绕过 profile_verified 锁，所有字段 COALESCE）。
     */
    @PatchMapping("/{userId}/profile")
    public ApiResponse<Void> updateProfile(@PathVariable long userId,
                                            @RequestHeader("X-Admin-User-Id") long adminUserId,
                                            @Valid @RequestBody AdminProfilePatchRequest request) {
        log.info("identity.internal.profile-patch.received userId={} adminUserId={}", userId, adminUserId);
        userAdminApplicationService.updateProfileByAdmin(userId, adminUserId, request);
        return success(null);
    }

    @GetMapping("/{userId}/kyc-status")
    public ApiResponse<KycStatusResponse> kycStatus(@PathVariable long userId,
                                                     @RequestHeader("X-Admin-User-Id") long callerId) {
        log.info("identity.internal.kyc-status.received userId={} callerId={}", userId, callerId);
        return success(userAdminApplicationService.getKycStatus(userId));
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
