package com.falconx.console.internal;

/**
 * STAGE-2-CUSTOMER：调用 internal RPC 失败异常（保留下游 code 用于跨服务错误码透传）。
 */
public class InternalRpcException extends RuntimeException {

    private final int httpStatus;
    private final String downstreamCode;
    private final String downstreamMessage;

    public InternalRpcException(int httpStatus, String body) {
        super("internal-rpc-failure http=" + httpStatus + " body=" + body);
        this.httpStatus = httpStatus;
        this.downstreamCode = null;
        this.downstreamMessage = body;
    }

    public InternalRpcException(int httpStatus, String downstreamCode, String downstreamMessage) {
        super("internal-rpc-failure http=" + httpStatus + " code=" + downstreamCode + " message=" + downstreamMessage);
        this.httpStatus = httpStatus;
        this.downstreamCode = downstreamCode;
        this.downstreamMessage = downstreamMessage;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getDownstreamCode() {
        return downstreamCode;
    }

    public String getDownstreamMessage() {
        return downstreamMessage;
    }
}
