package com.falconx.market.error;

import com.falconx.common.error.ErrorCode;

/**
 * FX 汇率子域错误码（60xxx 段）。
 */
public enum MarketFxErrorCode implements ErrorCode {
    FX_SYMBOL_NOT_FOUND("60010", "FX symbol 未在 t_symbol 中配置"),
    FX_RATE_STALE("60011", "FX rate 超过 stale 阈值，监控告警");

    private final String code;
    private final String message;

    MarketFxErrorCode(String code, String message) {
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
