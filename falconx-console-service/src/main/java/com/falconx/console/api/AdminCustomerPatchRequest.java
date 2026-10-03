package com.falconx.console.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理端编辑客户详情请求（PATCH /admin/customers/{userId}）。
 *
 * <p>四块独立可选 patch：
 * <ul>
 *   <li>{@code identity}：t_user 元数据（email / emailVerified / groupCode / kycLevel / status）</li>
 *   <li>{@code profile}：t_user_profile 16 字段（管理员可绕过 profile_verified 锁）</li>
 *   <li>{@code reason}：操作原因，必填 ≥ 10 字符（与 freeze/balance-adjust 同口径）</li>
 * </ul>
 * 任一块不传即跳过；reason 任何 patch 都必填。
 */
public record AdminCustomerPatchRequest(
        @Valid AdminUserPatchPayload identity,
        @Valid AdminProfilePatchPayload profile,
        @NotBlank @Size(min = 10, max = 500) String reason
) {

    public record AdminUserPatchPayload(
            @jakarta.validation.constraints.Email @Size(max = 128) String email,
            Boolean emailVerified,
            @Size(max = 64) String groupCode,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(1) Integer kycLevel,
            @jakarta.validation.constraints.Pattern(regexp = "ACTIVE|FROZEN|BANNED|PENDING_DEPOSIT") String status
    ) {
    }

    public record AdminProfilePatchPayload(
            @Size(max = 64) String firstName,
            @Size(max = 64) String middleName,
            @Size(max = 64) String lastName,
            java.time.LocalDate birthDate,
            @jakarta.validation.constraints.Pattern(regexp = "^[A-Z]{3}$") String nationality,
            Integer gender,
            @jakarta.validation.constraints.Pattern(regexp = "^[A-Z]{3}$") String residenceCountry,
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
}
