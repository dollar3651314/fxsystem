package com.falconx.wallet.config;

import com.falconx.common.api.ApiResponse;
import com.falconx.common.error.CommonErrorCode;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.wallet.error.WalletBusinessException;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * wallet-service 统一异常处理器。
 */
@RestControllerAdvice
public class WalletGlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WalletGlobalExceptionHandler.class);

    @ExceptionHandler(WalletBusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleWalletBusinessException(WalletBusinessException exception) {
        log.warn("wallet.request.failed code={} message={} context={}",
                exception.getErrorCode().code(),
                exception.getMessage(),
                exception.getContext());
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse<>(
                exception.getErrorCode().code(),
                exception.getMessage(),
                null,
                OffsetDateTime.now(),
                traceId()
        ));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ServletRequestBindingException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(Exception exception) {
        log.warn("wallet.request.invalid reason={}", exception.getMessage());
        return ResponseEntity.badRequest().body(new ApiResponse<>(
                CommonErrorCode.INVALID_REQUEST_PAYLOAD.code(),
                CommonErrorCode.INVALID_REQUEST_PAYLOAD.message(),
                null,
                OffsetDateTime.now(),
                traceId()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("wallet.request.unexpected", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiResponse<>(
                CommonErrorCode.INTERNAL_ERROR.code(),
                CommonErrorCode.INTERNAL_ERROR.message(),
                null,
                OffsetDateTime.now(),
                traceId()
        ));
    }

    private String traceId() {
        return MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY);
    }
}
