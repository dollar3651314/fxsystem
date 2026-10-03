package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.application.TradingAccountSnapshotApplicationService;
import com.falconx.trading.dto.TradingAccountResponse;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 交易账户查询控制器。
 *
 * <p>该控制器是 Stage 4 对外最小交易查询入口之一。
 * 它固定读取 gateway 注入的 `X-User-Id`，
 * 并返回当前用户在默认结算币种下的账户快照。
 */
@RestController
@RequestMapping("/api/v1/trading/accounts")
public class TradingAccountController {

    private static final Logger log = LoggerFactory.getLogger(TradingAccountController.class);

    private final TradingAccountSnapshotApplicationService tradingAccountSnapshotApplicationService;

    public TradingAccountController(TradingAccountSnapshotApplicationService tradingAccountSnapshotApplicationService) {
        this.tradingAccountSnapshotApplicationService = tradingAccountSnapshotApplicationService;
    }

    /**
     * 查询当前登录用户的交易账户快照。
     *
     * @param userId gateway 注入的用户主键
     * @return 统一响应结构下的账户快照
     */
    @GetMapping("/me")
    public ApiResponse<TradingAccountResponse> getCurrentAccount(@RequestHeader("X-User-Id") Long userId) {
        log.info("trading.http.account.received userId={}", userId);
        return new ApiResponse<>(
                "0",
                "success",
                tradingAccountSnapshotApplicationService.getCurrentAccountSnapshot(userId),
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }
}
