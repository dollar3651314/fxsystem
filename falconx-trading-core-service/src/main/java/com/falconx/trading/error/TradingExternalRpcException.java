package com.falconx.trading.error;

/**
 * STAGE-7-WITHDRAW：trading-core 调外部服务 internal RPC 失败时抛出。
 *
 * <p>携带下游 ApiResponse.code 与 message；上层应用服务可按需翻译成业务错误码或包装为 5xx。
 */
public class TradingExternalRpcException extends RuntimeException {

    private final int httpStatus;
    private final String downstreamCode;

    public TradingExternalRpcException(int httpStatus, String downstreamCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.downstreamCode = downstreamCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getDownstreamCode() {
        return downstreamCode;
    }
}
