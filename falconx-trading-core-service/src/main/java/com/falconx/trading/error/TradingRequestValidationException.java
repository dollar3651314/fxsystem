package com.falconx.trading.error;

/**
 * trading-core-service 请求参数校验异常。
 *
 * <p>该异常只用于把 controller 层识别到的非法请求体统一转换成稳定的 4xx 响应，
 * 不承载业务状态机语义。
 */
public class TradingRequestValidationException extends RuntimeException {

    public TradingRequestValidationException(String message) {
        super(message);
    }
}
