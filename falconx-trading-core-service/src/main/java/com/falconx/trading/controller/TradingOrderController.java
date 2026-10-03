package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.application.TradingAccountSnapshotApplicationService;
import com.falconx.trading.application.TradingOrderPlacementApplicationService;
import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.dto.OrderPlacementResult;
import com.falconx.trading.dto.PlaceMarketOrderRequest;
import com.falconx.trading.dto.PlaceMarketOrderResponse;
import com.falconx.trading.dto.TradingAccountResponse;
import com.falconx.trading.entity.TradingOrder;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.error.TradingErrorCode;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 市价单控制器。
 *
 * <p>该控制器负责暴露 Stage 4 最小交易写接口：
 * 当前只支持市价开仓骨架，用于打通 gateway -> trading-core-service 的外部闭环。
 */
@RestController
@RequestMapping("/api/v1/trading/orders")
public class TradingOrderController {

    private static final Logger log = LoggerFactory.getLogger(TradingOrderController.class);

    private final TradingOrderPlacementApplicationService tradingOrderPlacementApplicationService;
    private final TradingAccountSnapshotApplicationService tradingAccountSnapshotApplicationService;

    public TradingOrderController(TradingOrderPlacementApplicationService tradingOrderPlacementApplicationService,
                                  TradingAccountSnapshotApplicationService tradingAccountSnapshotApplicationService) {
        this.tradingOrderPlacementApplicationService = tradingOrderPlacementApplicationService;
        this.tradingAccountSnapshotApplicationService = tradingAccountSnapshotApplicationService;
    }

    /**
     * 提交一笔市价单。
     *
     * <p>该接口使用 gateway 注入的 `X-User-Id` 作为交易主体，
     * 不允许客户端直接在请求体内传递 `userId`，以避免越权和多来源歧义。
     *
     * @param userId gateway 注入的用户主键
     * @param request 市价单请求体
     * @return 下单结果；若被风控拒绝，按具体拒绝原因返回对应业务码
     */
    @PostMapping("/market")
    public ApiResponse<PlaceMarketOrderResponse> placeMarketOrder(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-User-Group-Code", required = false, defaultValue = "default") String groupCode,
            @Valid @RequestBody PlaceMarketOrderRequest request) {
        log.info("trading.http.order.received userId={} groupCode={} symbol={} clientOrderId={}",
                userId,
                groupCode,
                request.symbol(),
                request.clientOrderId());
        OrderPlacementResult result = tradingOrderPlacementApplicationService.placeMarketOrder(new PlaceMarketOrderCommand(
                userId,
                request.symbol(),
                request.side(),
                request.quantity(),
                request.leverage(),
                request.marginMode(),
                request.takeProfitPrice(),
                request.stopLossPrice(),
                request.clientOrderId(),
                groupCode
        ));

        PlaceMarketOrderResponse response = toResponse(result);
        if (result.rejectionReason() != null) {
            String code = "40002";
            String message = "Order Rejected";
            if ("SYMBOL_TRADING_SUSPENDED".equals(result.rejectionReason())) {
                code = "40008";
                message = "Symbol Trading Suspended";
            } else if ("MARGIN_MODE_NOT_SUPPORTED".equals(result.rejectionReason())) {
                code = "40010";
                message = "Margin Mode Not Supported";
            } else if ("CROSS_MODE_NOT_ENABLED".equals(result.rejectionReason())) {
                // STAGE-14D2 Task 2：CROSS 开仓但 cross_mode.enabled 关闭（master §7.3 错误码 30088）。
                code = TradingErrorCode.CROSS_MODE_NOT_ENABLED.code();
                message = TradingErrorCode.CROSS_MODE_NOT_ENABLED.message();
            } else if ("INSUFFICIENT_AVAILABLE_BALANCE".equals(result.rejectionReason())) {
                code = "40001";
                message = "Insufficient Margin";
            } else if ("MARKET_QUOTE_NOT_FOUND".equals(result.rejectionReason())) {
                code = TradingErrorCode.QUOTE_NOT_AVAILABLE.code();
                message = TradingErrorCode.QUOTE_NOT_AVAILABLE.message();
            } else if ("MARKET_QUOTE_STALE".equals(result.rejectionReason())) {
                code = TradingErrorCode.PRICE_SOURCE_STALE_OR_DISCONNECTED.code();
                message = TradingErrorCode.PRICE_SOURCE_STALE_OR_DISCONNECTED.message();
            } else if ("POSITION_LIMIT_REACHED".equals(result.rejectionReason())) {
                code = TradingErrorCode.POSITION_LIMIT_REACHED.code();
                message = TradingErrorCode.POSITION_LIMIT_REACHED.message();
            } else if ("PLATFORM_POSITION_LIMIT_REACHED".equals(result.rejectionReason())) {
                code = TradingErrorCode.PLATFORM_POSITION_LIMIT_REACHED.code();
                message = TradingErrorCode.PLATFORM_POSITION_LIMIT_REACHED.message();
            } else if ("LEVERAGE_EXCEEDS_TIER".equals(result.rejectionReason())) {
                // STAGE-14C1 Task 6：杠杆超出按 notional 分档的档位上限（master §7.3 错误码 30070）。
                // B 切片：客户端经 GET /symbols/{symbol}/leverage-tiers 取档位表换算「当前档位最大 Nx」提示。
                code = "30070";
                message = "Leverage Exceeds Tier";
            } else if ("LIQUIDATION_DISTANCE_TOO_CLOSE".equals(result.rejectionReason())) {
                // B 切片守卫（2026-06-03）：强平价距离 ≤ 点差×2，开仓即面临瞬时强平 → 拒单（错误码 30071）。
                code = "30071";
                message = "Liquidation Distance Too Close";
            } else if ("TIER_CONFIG_NOT_FOUND".equals(result.rejectionReason())) {
                // STAGE-14C1 Task 6：该 symbol+group 无杠杆/MM 档位配置或落不进任何档（master §7.3 错误码 30072）。
                code = "30072";
                message = "Tier Config Not Found";
            } else if ("GLOBAL_PAUSE_ACTIVE".equals(result.rejectionReason())) {
                // STAGE-14C2 Task 6：FX_PAUSED/GLOBAL_PAUSE 期间，该品种类目 allow_open=false 停开仓
                // （master §6.5 / §7.3 错误码 30087）。一刀切降级（category/behavior 缺失）仍走默认 40002。
                code = TradingErrorCode.GLOBAL_PAUSE_ACTIVE.code();
                message = TradingErrorCode.GLOBAL_PAUSE_ACTIVE.message();
            }
            return new ApiResponse<>(
                    code,
                    message,
                    response,
                    OffsetDateTime.now(),
                    MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
            );
        }
        return new ApiResponse<>(
                "0",
                "success",
                response,
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }

    private PlaceMarketOrderResponse toResponse(OrderPlacementResult result) {
        TradingOrder order = result.order();
        TradingPosition position = result.position();
        TradingTrade trade = result.trade();
        return new PlaceMarketOrderResponse(
                order.orderNo(),
                order.status().name(),
                result.rejectionReason(),
                result.duplicate(),
                order.symbol(),
                order.side().name(),
                order.quantity(),
                order.requestedPrice(),
                order.filledPrice(),
                order.leverage(),
                position == null || position.marginMode() == null ? null : position.marginMode().name(),
                order.margin(),
                order.fee(),
                position == null ? null : position.positionId(),
                position == null ? null : position.status().name(),
                position == null ? null : position.takeProfitPrice(),
                position == null ? null : position.stopLossPrice(),
                trade == null ? null : trade.tradeId(),
                tradingAccountSnapshotApplicationService.toResponse(result.account())
        );
    }
}
