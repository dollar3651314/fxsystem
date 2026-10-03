package com.falconx.market.config;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.market.error.MarketBusinessException;
import java.time.OffsetDateTime;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * market-service 统一异常处理器。
 *
 * <p>当前阶段该处理器只负责把市场查询相关业务异常转换为统一响应结构。
 */
@RestControllerAdvice
public class MarketGlobalExceptionHandler {

    @ExceptionHandler(MarketBusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleMarketBusinessException(MarketBusinessException exception) {
        return ResponseEntity.ok(new ApiResponse<>(
                exception.getCode(),
                exception.getMessage(),
                null,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        ));
    }
}
