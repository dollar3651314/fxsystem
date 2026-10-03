package com.falconx.identity.error;

/**
 * identity-service 业务异常。
 *
 * <p>该异常用于在注册、登录、刷新和激活链路中显式抛出可预期业务失败，
 * 由统一异常处理器映射到对应业务码。
 */
public class IdentityBusinessException extends RuntimeException {

    private final IdentityErrorCode errorCode;

    public IdentityBusinessException(IdentityErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public IdentityErrorCode getErrorCode() {
        return errorCode;
    }
}
