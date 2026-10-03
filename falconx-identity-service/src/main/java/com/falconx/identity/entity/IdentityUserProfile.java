package com.falconx.identity.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 用户基础资料 entity（STAGE-1B-USER-PROFILE）。
 *
 * <p>对应 {@code t_user_profile} 表，与 {@link IdentityUser} 1:1。
 *
 * <p>5 强制字段（first/middle/last/birth/nationality）注册时填写；
 * 其余字段在「个人资料」页主动补；{@code profileVerified=true}（KYC 通过后）
 * 锁定 5 强制字段，必须重新 KYC 才能改。
 */
public record IdentityUserProfile(
        Long userId,
        String firstName,
        String middleName,
        String lastName,
        LocalDate birthDate,
        String nationality,
        Integer gender,
        String residenceCountry,
        String residenceState,
        String residenceCity,
        String residenceAddress,
        String residencePostalCode,
        String phoneCountryCode,
        String phoneNumber,
        String languagePreference,
        String timezone,
        boolean profileVerified,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
