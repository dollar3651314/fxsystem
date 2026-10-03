package com.falconx.identity.contract.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 注册请求契约（STAGE-1B-USER-PROFILE 增强：注册时强制采集基础资料）。
 *
 * <p>用户决策（2026-05-09）：注册时强制 5 个 PII 字段（first/last/middle?/birth/nationality），
 * 注册成功后在同一事务里写入 t_user + t_user_profile。
 *
 * @param email        注册邮箱
 * @param password     明文密码，仅传输用，不得进日志
 * @param firstName    名（Given name），1-64 字符
 * @param middleName   中间名（可空）
 * @param lastName     姓（Family name），1-64 字符
 * @param birthDate    出生日期 ISO-8601 yyyy-MM-dd；后端校验 ≥ 18 周岁（10020）
 * @param nationality  国籍 ISO 3166-1 alpha-3，如 CHN/USA/JPN（10023）
 */
public record RegisterRequest(
        @NotBlank String email,
        @NotBlank String password,
        @NotBlank @Size(max = 64) String firstName,
        @Size(max = 64) String middleName,
        @NotBlank @Size(max = 64) String lastName,
        @NotNull LocalDate birthDate,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String nationality
) {
}
