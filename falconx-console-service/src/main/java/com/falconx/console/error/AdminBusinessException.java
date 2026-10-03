package com.falconx.console.error;

/**
 * 管理端业务异常。
 *
 * <p>用于在 controller / filter / service 链路中显式抛出可预期业务失败，
 * 由 {@code AdminGlobalExceptionHandler}（R9.5 引入）统一映射到 {@link com.falconx.common.api.ApiResponse}。
 *
 * <p>filter 链路中抛出本异常时，由 {@link com.falconx.console.security.AdminAuthenticationFilter}
 * 内部捕获并直接写入 401/403 JSON 响应（filter 抛异常不会流到 RestControllerAdvice）。
 */
public class AdminBusinessException extends RuntimeException {

    private final AdminErrorCode errorCode;

    public AdminBusinessException(AdminErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public AdminErrorCode getErrorCode() {
        return errorCode;
    }
}
