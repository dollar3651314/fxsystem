package com.falconx.console.repository.mapper.record;

import java.time.LocalDate;

/**
 * STAGE-1B-USER-PROFILE：跨 schema 查询 {@code falconx_identity.t_user_profile} 行记录。
 *
 * <p>console DB user 必须配置 identity schema 的 SELECT 权限（[`管理端架构`](docs/architecture/管理端架构.md) §1.3）。
 */
public record AdminCustomerProfileRecord(
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
        Integer profileVerified
) {
}
