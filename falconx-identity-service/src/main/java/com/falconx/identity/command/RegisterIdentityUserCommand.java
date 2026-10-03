package com.falconx.identity.command;

import java.time.LocalDate;

/**
 * 注册命令（STAGE-1B-USER-PROFILE 增强）。
 *
 * @param email        注册邮箱
 * @param password     明文密码
 * @param clientIp     客户端 IP
 * @param firstName    名（必填）
 * @param middleName   中间名（可空）
 * @param lastName     姓（必填）
 * @param birthDate    出生日期 (≥ 18 周岁，service 层校验)
 * @param nationality  国籍 ISO 3166-1 alpha-3（必填）
 */
public record RegisterIdentityUserCommand(
        String email,
        String password,
        String clientIp,
        String firstName,
        String middleName,
        String lastName,
        LocalDate birthDate,
        String nationality
) {
}
