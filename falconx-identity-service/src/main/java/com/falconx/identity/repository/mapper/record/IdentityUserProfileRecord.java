package com.falconx.identity.repository.mapper.record;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code t_user_profile} 行记录（STAGE-1B-USER-PROFILE）。
 */
public record IdentityUserProfileRecord(
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
        Integer profileVerified,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
