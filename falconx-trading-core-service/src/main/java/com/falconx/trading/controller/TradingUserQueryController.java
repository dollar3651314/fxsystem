package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.application.TradingUserQueryApplicationService;
import com.falconx.trading.command.ListTradingLedgerEntriesCommand;
import com.falconx.trading.command.ListTradingLiquidationsCommand;
import com.falconx.trading.command.ListTradingOrdersCommand;
import com.falconx.trading.command.ListTradingPositionsCommand;
import com.falconx.trading.command.ListTradingTradesCommand;
import com.falconx.trading.dto.TradingLedgerListResponse;
import com.falconx.trading.dto.TradingLeverageTierListResponse;
import com.falconx.trading.dto.TradingLiquidationListResponse;
import com.falconx.trading.dto.TradingOrderListResponse;
import com.falconx.trading.dto.TradingPositionListResponse;
import com.falconx.trading.dto.TradingPositionSummaryResponse;
import com.falconx.trading.dto.TradingTradeListResponse;
import com.falconx.trading.entity.TradingPositionStatus;
import com.falconx.trading.error.TradingRequestValidationException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户视角查询控制器。
 */
@RestController
@RequestMapping("/api/v1/trading")
public class TradingUserQueryController {

    private static final Logger log = LoggerFactory.getLogger(TradingUserQueryController.class);

    private final TradingUserQueryApplicationService tradingUserQueryApplicationService;

    public TradingUserQueryController(TradingUserQueryApplicationService tradingUserQueryApplicationService) {
        this.tradingUserQueryApplicationService = tradingUserQueryApplicationService;
    }

    /**
     * 杠杆/MM 档位查询（B 切片）：客户端下单面板按名义价值动态降档用。
     * 档位按调用方用户组解析（gateway 注入 X-User-Group-Code，组无配置回退 default），与开仓风控同源。
     */
    @GetMapping("/symbols/{symbol}/leverage-tiers")
    public ApiResponse<TradingLeverageTierListResponse> getLeverageTiers(
            @org.springframework.web.bind.annotation.PathVariable String symbol,
            @RequestHeader(value = "X-User-Group-Code", required = false, defaultValue = "default") String groupCode) {
        log.info("trading.http.leverage-tiers.received symbol={} groupCode={}", symbol, groupCode);
        return successResponse(tradingUserQueryApplicationService.getLeverageTiers(symbol, groupCode));
    }

    @GetMapping("/orders")
    public ApiResponse<TradingOrderListResponse> listOrders(@RequestHeader("X-User-Id") Long userId,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int pageSize) {
        validatePage(page, pageSize);
        log.info("trading.http.orders.received userId={} page={} pageSize={}", userId, page, pageSize);
        TradingOrderListResponse response = tradingUserQueryApplicationService.listOrders(
                new ListTradingOrdersCommand(userId, page, pageSize)
        );
        return successResponse(response);
    }

    @GetMapping("/trades")
    public ApiResponse<TradingTradeListResponse> listTrades(@RequestHeader("X-User-Id") Long userId,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int pageSize) {
        validatePage(page, pageSize);
        log.info("trading.http.trades.received userId={} page={} pageSize={}", userId, page, pageSize);
        TradingTradeListResponse response = tradingUserQueryApplicationService.listTrades(
                new ListTradingTradesCommand(userId, page, pageSize)
        );
        return successResponse(response);
    }

    @GetMapping("/positions")
    public ApiResponse<TradingPositionListResponse> listPositions(@RequestHeader("X-User-Id") Long userId,
                                                                  @RequestParam(defaultValue = "1") int page,
                                                                  @RequestParam(defaultValue = "20") int pageSize,
                                                                  @RequestParam(required = false) String status) {
        validatePage(page, pageSize);
        List<TradingPositionStatus> statusFilter = parseStatusFilter(status);
        log.info("trading.http.positions.received userId={} page={} pageSize={} statusFilter={}",
                userId, page, pageSize, statusFilter);
        TradingPositionListResponse response = tradingUserQueryApplicationService.listPositions(
                new ListTradingPositionsCommand(userId, page, pageSize, statusFilter)
        );
        return successResponse(response);
    }

    /**
     * 解析 status query 参数：支持单个（"OPEN"）或逗号分隔（"CLOSED,LIQUIDATED"）。
     * null / 空字符串 → 返回 null 表示不过滤；任何非法 enum 名一律抛 400 而不是静默忽略。
     */
    private List<TradingPositionStatus> parseStatusFilter(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return Arrays.stream(status.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(TradingPositionStatus::valueOf)
                    .toList();
        } catch (IllegalArgumentException ex) {
            throw new TradingRequestValidationException(
                    "status must be one of OPEN, CLOSED, LIQUIDATED (comma-separated allowed)");
        }
    }

    /**
     * 用户持仓汇总：OPEN 持仓笔数 + 占用保证金合计 + 总未实现盈亏。
     * 用于 dashboard / market 顶部「未实现盈亏」卡的首次渲染初值；之后由 WS
     * 的 user.position.summary 事件实时刷新。
     */
    @GetMapping("/positions/summary")
    public ApiResponse<TradingPositionSummaryResponse> getPositionSummary(
            @RequestHeader("X-User-Id") Long userId) {
        log.info("trading.http.positions.summary.received userId={}", userId);
        return successResponse(tradingUserQueryApplicationService.getPositionSummary(userId));
    }

    @GetMapping("/ledger")
    public ApiResponse<TradingLedgerListResponse> listLedgerEntries(@RequestHeader("X-User-Id") Long userId,
                                                                    @RequestParam(defaultValue = "1") int page,
                                                                    @RequestParam(defaultValue = "20") int pageSize,
                                                                    @RequestParam(required = false) String bizType,
                                                                    @RequestParam(required = false) String from,
                                                                    @RequestParam(required = false) String to) {
        validatePage(page, pageSize);
        com.falconx.trading.entity.TradingLedgerBizType bizTypeEnum = null;
        if (bizType != null && !bizType.isBlank()) {
            try {
                bizTypeEnum = com.falconx.trading.entity.TradingLedgerBizType.valueOf(bizType);
            } catch (IllegalArgumentException e) {
                throw new TradingRequestValidationException("invalid bizType: " + bizType);
            }
        }
        java.time.OffsetDateTime fromDt = parseOffsetDateTime(from, "from");
        java.time.OffsetDateTime toDt = parseOffsetDateTime(to, "to");
        log.info("trading.http.ledger.received userId={} page={} pageSize={} bizType={} from={} to={}",
                userId, page, pageSize, bizTypeEnum, fromDt, toDt);
        TradingLedgerListResponse response = tradingUserQueryApplicationService.listLedgerEntries(
                new ListTradingLedgerEntriesCommand(userId, page, pageSize, bizTypeEnum, fromDt, toDt)
        );
        return successResponse(response);
    }

    private java.time.OffsetDateTime parseOffsetDateTime(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) return null;
        try {
            // 支持完整 ISO（带 offset）+ 本地 datetime（无 offset，按 UTC 兜底）两种形态
            try {
                return java.time.OffsetDateTime.parse(raw);
            } catch (java.time.format.DateTimeParseException ignored) {
                return java.time.LocalDateTime.parse(raw).atOffset(java.time.ZoneOffset.UTC);
            }
        } catch (java.time.format.DateTimeParseException e) {
            throw new TradingRequestValidationException("invalid datetime for " + fieldName + ": " + raw);
        }
    }

    @GetMapping("/liquidations")
    public ApiResponse<TradingLiquidationListResponse> listLiquidations(@RequestHeader("X-User-Id") Long userId,
                                                                        @RequestParam(defaultValue = "1") int page,
                                                                        @RequestParam(defaultValue = "20") int pageSize) {
        validatePage(page, pageSize);
        log.info("trading.http.liquidations.received userId={} page={} pageSize={}", userId, page, pageSize);
        TradingLiquidationListResponse response = tradingUserQueryApplicationService.listLiquidations(
                new ListTradingLiquidationsCommand(userId, page, pageSize)
        );
        return successResponse(response);
    }

    @GetMapping("/positions/{positionId}/swap-summary")
    public ApiResponse<com.falconx.trading.dto.TradingSwapSummaryResponse> getPositionSwapSummary(
            @RequestHeader("X-User-Id") Long userId,
            @org.springframework.web.bind.annotation.PathVariable Long positionId) {
        log.info("trading.http.swap-summary.position.received userId={} positionId={}", userId, positionId);
        return successResponse(tradingUserQueryApplicationService.getPositionSwapSummary(userId, positionId));
    }

    @GetMapping("/account/swap-summary")
    public ApiResponse<com.falconx.trading.dto.TradingSwapSummaryResponse> getAccountSwapSummary(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(defaultValue = "30") int days) {
        if (days < 1 || days > 365) {
            throw new TradingRequestValidationException("days must be between 1 and 365");
        }
        log.info("trading.http.swap-summary.account.received userId={} days={}", userId, days);
        return successResponse(tradingUserQueryApplicationService.getAccountSwapSummary(userId, days));
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1) {
            throw new TradingRequestValidationException("page must be greater than or equal to 1");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new TradingRequestValidationException("pageSize must be between 1 and 100");
        }
    }

    private <T> ApiResponse<T> successResponse(T data) {
        return new ApiResponse<>(
                "0",
                "success",
                data,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }
}
