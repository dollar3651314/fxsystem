package com.falconx.trading.config;

import com.falconx.common.api.ApiResponse;
import com.falconx.common.error.CommonErrorCode;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingRequestValidationException;
import jakarta.validation.ConstraintViolationException;
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
 * trading-core-service 统一异常处理器。
 *
 * <p>Stage 4 引入外部控制器后，需要把请求头缺失、参数校验失败和意外异常
 * 全部统一转换成 FalconX 响应结构，避免 controller 各自处理异常分支。
 */
@RestControllerAdvice
public class TradingGlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TradingGlobalExceptionHandler.class);

    /**
     * 处理交易业务异常。
     *
     * @param exception 业务异常
     * @return 统一 200 业务失败响应
     */
    @ExceptionHandler(TradingBusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleTradingBusinessException(TradingBusinessException exception) {
        Object userId = exception.getContext().get("userId");
        Object symbol = exception.getContext().get("symbol");
        Object rejectionReason = exception.getContext().get("rejectionReason");
        Object positionId = exception.getContext().get("positionId");
        log.error("trading.http.request.failed code={} message={} userId={} symbol={} positionId={} rejectionReason={}",
                exception.getErrorCode().code(),
                exception.getMessage(),
                userId,
                symbol,
                positionId,
                rejectionReason);
        return ResponseEntity.ok(new ApiResponse<>(
                exception.getErrorCode().code(),
                exception.getMessage(),
                null,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        ));
    }

    /**
     * 处理 trading-core-service 显式请求校验异常。
     *
     * @param exception 请求校验异常
     * @return 统一 400 响应
     */
    @ExceptionHandler(TradingRequestValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleTradingRequestValidationException(TradingRequestValidationException exception) {
        log.warn("trading.http.request.invalid reason={}", exception.getMessage());
        return ResponseEntity.badRequest().body(new ApiResponse<>(
                CommonErrorCode.INVALID_REQUEST_PAYLOAD.code(),
                CommonErrorCode.INVALID_REQUEST_PAYLOAD.message(),
                null,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        ));
    }

    /**
     * 处理请求头缺失、Bean Validation 失败和 JSON 反序列化失败。
     *
     * @param exception 请求异常
     * @return 统一 400 响应
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            ServletRequestBindingException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception exception) {
        log.warn("trading.http.request.invalid reason={}", exception.getMessage());
        return ResponseEntity.badRequest().body(new ApiResponse<>(
                CommonErrorCode.INVALID_REQUEST_PAYLOAD.code(),
                CommonErrorCode.INVALID_REQUEST_PAYLOAD.message(),
                null,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        ));
    }

    /**
     * 处理未预期异常。
     *
     * @param exception 未预期异常
     * @return 统一 500 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("trading.http.request.unexpected", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiResponse<>(
                CommonErrorCode.INTERNAL_ERROR.code(),
                CommonErrorCode.INTERNAL_ERROR.message(),
                null,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        ));
    }
}
