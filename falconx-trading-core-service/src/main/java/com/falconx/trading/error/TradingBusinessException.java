package com.falconx.trading.error;

import java.util.Map;

/**
 * trading-core-service 业务异常。
 *
 * <p>该异常用于把平仓等可预期业务失败统一交给全局异常处理器映射为标准响应结构。
 */
public class TradingBusinessException extends RuntimeException {

    private final TradingErrorCode errorCode;
    private final Map<String, Object> context;

    public TradingBusinessException(TradingErrorCode errorCode) {
        this(errorCode, Map.of());
    }

    public TradingBusinessException(TradingErrorCode errorCode, Map<String, Object> context) {
        super(errorCode.message());
        this.errorCode = errorCode;
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }

    public TradingErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getContext() {
        return context;
    }
}
