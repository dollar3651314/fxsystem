package com.falconx.console.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 管理端 refresh token 刷新请求。
 *
 * @param refreshToken refresh token 字符串
 */
public record AdminRefreshRequest(@NotBlank String refreshToken) {
}
