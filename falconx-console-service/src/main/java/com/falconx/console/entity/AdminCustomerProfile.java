package com.falconx.console.entity;

import java.time.LocalDate;

/**
 * STAGE-1B-USER-PROFILE：客户基础资料（跨 schema 查询 identity.t_user_profile）。
 *
 * <p>客户管理详情接口的 profile 子结构，与 identity 域 IdentityUserProfile 字段一致；
 * 复制为独立 entity 以保持 console 域边界。
 */
public record AdminCustomerProfile(
        long userId,
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
        boolean profileVerified
) {
}
