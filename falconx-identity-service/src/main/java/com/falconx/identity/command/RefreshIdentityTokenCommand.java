package com.falconx.identity.command;

/**
 * 刷新 token 命令。
 *
 * @param refreshToken 待刷新的 Refresh Token
 */
public record RefreshIdentityTokenCommand(
        String refreshToken
) {
}
