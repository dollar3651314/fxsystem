package com.falconx.identity.command;

/**
 * 登录命令。
 *
 * @param email 登录邮箱
 * @param password 明文密码
 * @param clientIp 客户端 IP
 */
public record LoginIdentityUserCommand(
        String email,
        String password,
        String clientIp
) {
}
