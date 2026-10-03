package com.falconx.identity.contract.profile;

import java.time.LocalDate;

/**
 * 用户基础资料响应（STAGE-1B-USER-PROFILE）。
 *
 * <p>{@code GET /api/v1/me/profile} + {@code PUT /api/v1/me/profile} 的响应结构。
 *
 * <p>Note: userId 雪花 ID 在响应中以 String 序列化，避免 JS Number 精度丢失（FX-071 模式）。
 */
public record UserProfileResponse(
        String userId,
        // 5 强制字段
        String firstName,
        String middleName,
        String lastName,
        LocalDate birthDate,
        String nationality,
        // 可选字段
        Integer gender,                  // 1=MALE / 2=FEMALE / 9=OTHER
        String residenceCountry,
        String residenceState,
        String residenceCity,
        String residenceAddress,
        String residencePostalCode,
        String phoneCountryCode,
        String phoneNumber,
        // 偏好
        String languagePreference,
        String timezone,
        // KYC 状态
        boolean profileVerified
) {
}
