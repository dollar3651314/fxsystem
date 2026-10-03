package com.falconx.gateway.error;

import com.falconx.common.error.ErrorCode;

/**
 * gateway 专属错误码。
 *
 * <p>该枚举只承载 gateway 自身的限流类业务码，
 * 与认证和系统级错误码一起构成当前 Stage 6B 的网关对外失败语义。
 */
public enum GatewayErrorCode implements ErrorCode {
    TRADING_RATE_LIMITED("10012", "Trading Rate Limited"),
    GLOBAL_IP_RATE_LIMITED("10013", "Global IP Rate Limited");

    private final String code;
    private final String message;

    GatewayErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
