package com.falconx.console.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.console.api.AdminSystemConfigListResponse;
import com.falconx.console.systemconfig.SystemConfigApplicationService;
import com.falconx.infrastructure.trace.TraceIdConstants;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-13 系统配置中心 — internal RPC controller。
 *
 * <p>各业务 service 启动时调 {@code GET /internal/v1/system-config} 全量拉取配置。
 * 鉴权：校验 {@code X-Internal-Token} header（同 gateway 注入 internal token 一致）。
 * 不需要 admin JWT —— service-to-service 调用直接走 internal token。
 */
@RestController
@RequestMapping("/internal/v1/system-config")
public class InternalSystemConfigController {

    private static final Logger log = LoggerFactory.getLogger(InternalSystemConfigController.class);

    private final SystemConfigApplicationService applicationService;
    private final String internalApiToken;

    public InternalSystemConfigController(SystemConfigApplicationService applicationService,
                                          @Value("${falconx.console.internal-api.token}") String internalApiToken) {
        this.applicationService = applicationService;
        this.internalApiToken = internalApiToken;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<AdminSystemConfigListResponse>> listAll(HttpServletRequest request) {
        if (!authorize(request)) {
            log.warn("internal.system-config.unauthorized client-ip={}", request.getRemoteAddr());
            return ResponseEntity.status(401).body(unauthorized());
        }
        var items = applicationService.listAll().stream()
                .map(AdminSystemConfigListResponse.Item::from)
                .toList();
        log.info("internal.system-config.list count={}", items.size());
        return ResponseEntity.ok(success(new AdminSystemConfigListResponse(items)));
    }

    private boolean authorize(HttpServletRequest request) {
        String token = request.getHeader("X-Internal-Token");
        return internalApiToken != null && !internalApiToken.isBlank() && internalApiToken.equals(token);
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                "0",
                "success",
                data,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }

    private <T> ApiResponse<T> unauthorized() {
        return new ApiResponse<>(
                "10001",
                "unauthorized",
                null,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }
}
