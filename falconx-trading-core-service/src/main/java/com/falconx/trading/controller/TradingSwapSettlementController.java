package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.application.TradingSwapSettlementQueryApplicationService;
import com.falconx.trading.command.ListTradingSwapSettlementsCommand;
import com.falconx.trading.dto.TradingSwapSettlementListResponse;
import com.falconx.trading.error.TradingRequestValidationException;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * `Swap` 明细查询控制器。
 */
@RestController
@RequestMapping("/api/v1/trading/swap-settlements")
public class TradingSwapSettlementController {

    private static final Logger log = LoggerFactory.getLogger(TradingSwapSettlementController.class);

    private final TradingSwapSettlementQueryApplicationService tradingSwapSettlementQueryApplicationService;

    public TradingSwapSettlementController(TradingSwapSettlementQueryApplicationService tradingSwapSettlementQueryApplicationService) {
        this.tradingSwapSettlementQueryApplicationService = tradingSwapSettlementQueryApplicationService;
    }

    /**
     * 查询当前用户的 `Swap` 明细分页。
     */
    @GetMapping
    public ApiResponse<TradingSwapSettlementListResponse> listSwapSettlements(@RequestHeader("X-User-Id") Long userId,
                                                                              @RequestParam(defaultValue = "1") int page,
                                                                              @RequestParam(defaultValue = "20") int pageSize) {
        validatePage(page, pageSize);
        log.info("trading.http.swap.settlements.received userId={} page={} pageSize={}", userId, page, pageSize);
        TradingSwapSettlementListResponse response = tradingSwapSettlementQueryApplicationService.listSwapSettlements(
                new ListTradingSwapSettlementsCommand(userId, page, pageSize)
        );
        return new ApiResponse<>(
                "0",
                "success",
                response,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1) {
            throw new TradingRequestValidationException("page must be greater than or equal to 1");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new TradingRequestValidationException("pageSize must be between 1 and 100");
        }
    }
}
