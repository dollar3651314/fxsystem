package com.falconx.identity.config;

import com.falconx.common.api.ApiResponse;
import com.falconx.common.error.CommonErrorCode;
import com.falconx.identity.error.IdentityBusinessException;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * identity-service 统一异常处理器。
 *
 * <p>该处理器负责把身份域骨架阶段产生的业务异常和请求异常
 * 统一转换为 FalconX 响应结构，并输出符合日志规范的错误日志。
 */
@RestControllerAdvice
public class IdentityGlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IdentityGlobalExceptionHandler.class);

    @ExceptionHandler(IdentityBusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdentityBusinessException(IdentityBusinessException exception) {
        log.warn("identity.request.failed code={} message={}", exception.getErrorCode().code(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse<>(
                exception.getErrorCode().code(),
                exception.getMessage(),
                null,
                OffsetDateTime.now(),
                MDC.get("traceId")
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException exception) {
        log.warn("identity.request.invalid reason={}", exception.getMessage());
        return ResponseEntity.badRequest().body(new ApiResponse<>(
                CommonErrorCode.INVALID_REQUEST_PAYLOAD.code(),
                CommonErrorCode.INVALID_REQUEST_PAYLOAD.message(),
                null,
                OffsetDateTime.now(),
                MDC.get("traceId")
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("identity.request.unexpected", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiResponse<>(
                CommonErrorCode.INTERNAL_ERROR.code(),
                CommonErrorCode.INTERNAL_ERROR.message(),
                null,
                OffsetDateTime.now(),
                MDC.get("traceId")
        ));
    }
}
