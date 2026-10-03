package com.falconx.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理端 patch t_user 元数据请求。
 *
 * <p>所有字段都允许 null（COALESCE 保留原值），调用方按需传递。
 *
 * @param email          新邮箱（管理员强制覆盖，不触发重新验证流程）
 * @param emailVerified  null = 不改；true / false → 1/0 写入
 * @param groupCode      用户组（如 default / vip-asia）
 * @param kycLevel       0 = 未认证 / 1 = 已通过简单 KYC
 * @param status         {@link com.falconx.domain.enums.UserStatus} 枚举名，
 *                       如 ACTIVE / FROZEN / BANNED / PENDING_DEPOSIT
 */
public record AdminUserPatchRequest(
        @Email @Size(max = 128) String email,
        Boolean emailVerified,
        @Size(max = 64) String groupCode,
        @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(1) Integer kycLevel,
        @Pattern(regexp = "ACTIVE|FROZEN|BANNED|PENDING_DEPOSIT") String status
) {
}
