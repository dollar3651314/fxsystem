package com.falconx.identity.contract.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Refresh Token 刷新请求契约。
 *
 * <p>一期刷新接口只接受单个 Refresh Token，
 * 并执行一次性使用与轮换。
 *
 * @param refreshToken 上一次登录或刷新返回的 Refresh Token
 */
public record RefreshTokenRequest(
        @NotBlank String refreshToken
) {
}
