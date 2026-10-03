package com.falconx.identity.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 管理端 patch t_user_profile 请求。
 *
 * <p>与用户自助 {@link com.falconx.identity.contract.profile.UpdateProfileRequest} 的区别：
 * <ul>
 *   <li>不受 profile_verified 锁限制（管理员强制覆盖）</li>
 *   <li>所有字段都允许 null（COALESCE 保留原值）</li>
 *   <li>不更新 profile_verified 标记（管理员改名不应触发"已认证"误判）</li>
 * </ul>
 */
public record AdminProfilePatchRequest(
        @Size(max = 64) String firstName,
        @Size(max = 64) String middleName,
        @Size(max = 64) String lastName,
        LocalDate birthDate,
        @Pattern(regexp = "^[A-Z]{3}$") String nationality,

        Integer gender,                  // 1=男 / 2=女 / 9=不愿告知
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
