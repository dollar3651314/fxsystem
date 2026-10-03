package com.falconx.identity.contract.profile;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 用户主动更新基础资料请求（STAGE-1B-USER-PROFILE）。
 *
 * <p>所有字段都可选；service 层判断哪些字段非空 → 落库；忽略 null 字段。
 *
 * <p>5 强制字段（first/middle/last/birth/nationality）受 {@code profile_verified=1} 锁定，
 * 锁定后传值会触发 10022 USER_PROFILE_VERIFIED_LOCKED；
 * 可选字段（gender、residence_、phone_、language、timezone）任何时候可改。
 */
public record UpdateProfileRequest(
        @Size(max = 64) String firstName,
        @Size(max = 64) String middleName,
        @Size(max = 64) String lastName,
        LocalDate birthDate,
        @Pattern(regexp = "^[A-Z]{3}$") String nationality,

        Integer gender,                  // 1/2/9
        @Pattern(regexp = "^[A-Z]{3}$") String residenceCountry,
        @Size(max = 64) String residenceState,
        @Size(max = 64) String residenceCity,
        @Size(max = 255) String residenceAddress,
        @Size(max = 32) String residencePostalCode,
        @Size(max = 8) String phoneCountryCode,
        @Size(max = 32) String phoneNumber,

        @Size(max = 16) String languagePreference,
        @Size(max = 64) String timezone
) {
}
