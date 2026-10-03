package com.falconx.gateway.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.common.error.CommonErrorCode;
import com.falconx.infrastructure.trace.TraceIdConstants;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * gateway 下游失败回退入口。
 *
 * <p>该控制器只负责把下游超时、熔断等网关级失败
 * 收敛成统一的 `99002` (DEPENDENCY_TIMEOUT) 响应，避免错误直接泄漏给客户端。
 */
@RestController
@RequestMapping("/internal/gateway/fallback")
public class GatewayFallbackController {

    private static final Logger log = LoggerFactory.getLogger(GatewayFallbackController.class);

    @RequestMapping("/{routeId}")
    public ResponseEntity<ApiResponse<Void>> fallback(@PathVariable String routeId,
                                                      @RequestHeader(value = TraceIdConstants.TRACE_ID_HEADER,
                                                              required = false) String traceId) {
        log.error("gateway.route.fallback routeId={} reason=dependency_timeout_or_circuit_open", routeId);
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(new ApiResponse<>(
                CommonErrorCode.DEPENDENCY_TIMEOUT.code(),
                CommonErrorCode.DEPENDENCY_TIMEOUT.message(),
                null,
                OffsetDateTime.now(),
                traceId
        ));
    }
}
