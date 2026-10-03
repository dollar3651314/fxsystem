package com.falconx.identity.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.identity.application.IdentityProfileApplicationService;
import com.falconx.identity.contract.profile.UpdateProfileRequest;
import com.falconx.identity.contract.profile.UserProfileResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户基础资料 REST controller（STAGE-1B-USER-PROFILE）。
 *
 * <p>userId 由 gateway 从 JWT 解析后通过 {@code X-User-Id} header 透传，本 controller 直接信任。
 */
@RestController
@RequestMapping("/api/v1/me/profile")
public class UserProfileController {

    private static final Logger log = LoggerFactory.getLogger(UserProfileController.class);

    private final IdentityProfileApplicationService applicationService;

    public UserProfileController(IdentityProfileApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping
    public ApiResponse<UserProfileResponse> getProfile(@RequestHeader("X-User-Id") Long userId) {
        log.info("identity.http.profile.get.received userId={}", userId);
        return success(applicationService.getProfile(userId));
    }

    @PutMapping
    public ApiResponse<UserProfileResponse> updateProfile(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody UpdateProfileRequest request) {
        log.info("identity.http.profile.put.received userId={}", userId);
        return success(applicationService.updateProfile(userId, request));
    }

    private static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
