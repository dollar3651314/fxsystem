package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.api.AdminBalanceAdjustRequest;
import com.falconx.trading.api.AdminBalanceAdjustResponse;
import com.falconx.trading.application.TradingAccountAdminApplicationService;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-2-CUSTOMER：trading-core 内部 RPC（仅接受来自 console-service 通过 gateway 的调用）。
 *
 * <p>路径：{@code /internal/v1/trading/accounts/{userId}/balance/adjust}
 *
 * <p>鉴权：{@link com.falconx.trading.security.TradingInternalApiTokenFilter} 校验
 * {@code X-Internal-Token} + {@code X-Admin-User-Id}。
 *
 * <p>详细契约见 [`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §4.3。
 */
@RestController
@RequestMapping("/internal/v1/trading/accounts")
public class AdminInternalAccountController {

    private static final Logger log = LoggerFactory.getLogger(AdminInternalAccountController.class);

    private final TradingAccountAdminApplicationService accountAdminApplicationService;

    public AdminInternalAccountController(TradingAccountAdminApplicationService accountAdminApplicationService) {
        this.accountAdminApplicationService = accountAdminApplicationService;
    }

    @PostMapping("/{userId}/balance/adjust")
    public ApiResponse<AdminBalanceAdjustResponse> adjustBalance(@PathVariable long userId,
                                                                 @RequestHeader("X-Admin-User-Id") long adminUserId,
                                                                 @Valid @RequestBody AdminBalanceAdjustRequest request) {
        String referenceNo = MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY);
        if (referenceNo == null || referenceNo.isBlank()) {
            referenceNo = "no-trace-" + UUID.randomUUID();
        }
        log.info("trading.internal.balance-adjust.received userId={} adminUserId={} delta={}",
                userId, adminUserId, request.deltaUSD());
        AdminBalanceAdjustResponse response = accountAdminApplicationService.adjustBalance(
                userId, adminUserId, request.deltaUSD(), request.reason(), referenceNo);
        return success(response);
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
}
