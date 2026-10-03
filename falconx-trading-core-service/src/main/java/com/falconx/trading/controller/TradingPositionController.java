package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.application.TradingAccountSnapshotApplicationService;
import com.falconx.trading.application.TradingPositionCloseApplicationService;
import com.falconx.trading.application.TradingPositionMarginApplicationService;
import com.falconx.trading.application.TradingPositionRiskControlsApplicationService;
import com.falconx.trading.command.AddIsolatedMarginCommand;
import com.falconx.trading.command.CloseTradingPositionCommand;
import com.falconx.trading.command.UpdatePositionRiskControlsCommand;
import com.falconx.trading.dto.AddIsolatedMarginRequest;
import com.falconx.trading.dto.AddIsolatedMarginResponse;
import com.falconx.trading.dto.AddIsolatedMarginResult;
import com.falconx.trading.dto.CloseTradingPositionResponse;
import com.falconx.trading.dto.PositionCloseResult;
import com.falconx.trading.dto.UpdateTradingPositionRiskControlsResponse;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingTrade;
import com.falconx.trading.error.TradingRequestValidationException;
import com.falconx.trading.support.TradingPricingSupport;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 持仓控制器。
 *
 * <p>当前阶段开放两个最小持仓写接口：
 *
 * <ul>
 *   <li>手动平仓</li>
 *   <li>修改 OPEN 持仓 TP/SL</li>
 *   <li>追加逐仓保证金</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/trading/positions")
public class TradingPositionController {

    private static final Logger log = LoggerFactory.getLogger(TradingPositionController.class);

    private final TradingPositionCloseApplicationService tradingPositionCloseApplicationService;
    private final TradingPositionMarginApplicationService tradingPositionMarginApplicationService;
    private final TradingPositionRiskControlsApplicationService tradingPositionRiskControlsApplicationService;
    private final TradingAccountSnapshotApplicationService tradingAccountSnapshotApplicationService;

    public TradingPositionController(TradingPositionCloseApplicationService tradingPositionCloseApplicationService,
                                     TradingPositionMarginApplicationService tradingPositionMarginApplicationService,
                                     TradingPositionRiskControlsApplicationService tradingPositionRiskControlsApplicationService,
                                     TradingAccountSnapshotApplicationService tradingAccountSnapshotApplicationService) {
        this.tradingPositionCloseApplicationService = tradingPositionCloseApplicationService;
        this.tradingPositionMarginApplicationService = tradingPositionMarginApplicationService;
        this.tradingPositionRiskControlsApplicationService = tradingPositionRiskControlsApplicationService;
        this.tradingAccountSnapshotApplicationService = tradingAccountSnapshotApplicationService;
    }

    /**
     * 手动平掉一笔 OPEN 持仓。
     *
     * @param userId gateway 注入的用户主键
     * @param positionId 持仓主键
     * @return 平仓后的持仓、成交与账户快照
     */
    @PostMapping("/{positionId}/close")
    public ApiResponse<CloseTradingPositionResponse> closePosition(@RequestHeader("X-User-Id") Long userId,
                                                                   @PathVariable("positionId") Long positionId) {
        log.info("trading.http.position.close.received userId={} positionId={}", userId, positionId);
        PositionCloseResult result = tradingPositionCloseApplicationService.closePosition(
                new CloseTradingPositionCommand(userId, positionId)
        );
        return new ApiResponse<>(
                "0",
                "success",
                toResponse(result),
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }

    /**
     * 为一笔 OPEN 持仓追加逐仓保证金。
     */
    @PostMapping("/{positionId}/margin")
    public ApiResponse<AddIsolatedMarginResponse> addIsolatedMargin(@RequestHeader("X-User-Id") Long userId,
                                                                    @PathVariable("positionId") Long positionId,
                                                                    @Valid @RequestBody AddIsolatedMarginRequest request) {
        log.info("trading.http.position.margin.received userId={} positionId={} amount={}",
                userId,
                positionId,
                request.amount());
        AddIsolatedMarginResult result = tradingPositionMarginApplicationService.addIsolatedMargin(new AddIsolatedMarginCommand(
                userId,
                positionId,
                request.amount()
        ));
        return new ApiResponse<>(
                "0",
                "success",
                new AddIsolatedMarginResponse(
                        result.position().positionId(),
                        result.position().symbol(),
                        result.position().status().name(),
                        result.position().marginMode().name(),
                        result.position().margin(),
                        result.position().liquidationPrice(),
                        tradingAccountSnapshotApplicationService.toResponse(result.account())
                ),
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }

    /**
     * 修改 OPEN 持仓 TP/SL。
     */
    @PatchMapping("/{positionId}")
    public ApiResponse<UpdateTradingPositionRiskControlsResponse> updatePositionRiskControls(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable("positionId") Long positionId,
            @RequestBody(required = false) Map<String, Object> requestBody) {
        log.info("trading.http.position.patch.received userId={} positionId={}", userId, positionId);
        UpdatePositionRiskControlsCommand command = parseUpdatePositionRiskControlsCommand(userId, positionId, requestBody);
        TradingPosition updatedPosition = tradingPositionRiskControlsApplicationService.updateRiskControls(
                command
        );
        return new ApiResponse<>(
                "0",
                "success",
                new UpdateTradingPositionRiskControlsResponse(
                        updatedPosition.positionId(),
                        updatedPosition.symbol(),
                        updatedPosition.status().name(),
                        updatedPosition.takeProfitPrice(),
                        updatedPosition.stopLossPrice()
                ),
                OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY)
        );
    }

    private UpdatePositionRiskControlsCommand parseUpdatePositionRiskControlsCommand(Long userId,
                                                                                     Long positionId,
                                                                                     Map<String, Object> requestBody) {
        if (requestBody == null) {
            throw new TradingRequestValidationException("PATCH /positions requires a JSON object body");
        }

        FieldValue takeProfitPrice = parsePositiveNullableDecimal(requestBody, "takeProfitPrice");
        FieldValue stopLossPrice = parsePositiveNullableDecimal(requestBody, "stopLossPrice");
        if (!takeProfitPrice.provided() && !stopLossPrice.provided()) {
            throw new TradingRequestValidationException("PATCH /positions requires takeProfitPrice or stopLossPrice");
        }
        return new UpdatePositionRiskControlsCommand(
                userId,
                positionId,
                takeProfitPrice.provided(),
                takeProfitPrice.value(),
                stopLossPrice.provided(),
                stopLossPrice.value()
        );
    }

    private CloseTradingPositionResponse toResponse(PositionCloseResult result) {
        TradingPosition position = result.position();
        TradingTrade trade = result.trade();
        return new CloseTradingPositionResponse(
                position.positionId(),
                position.status().name(),
                position.closePrice(),
                position.closeReason().name(),
                position.realizedPnl(),
                position.closedAt(),
                trade.tradeId(),
                tradingAccountSnapshotApplicationService.toResponse(result.account())
        );
    }

    private FieldValue parsePositiveNullableDecimal(Map<String, Object> requestBody, String fieldName) {
        if (!requestBody.containsKey(fieldName)) {
            return new FieldValue(false, null);
        }
        Object fieldValue = requestBody.get(fieldName);
        if (fieldValue == null) {
            return new FieldValue(true, null);
        }
        if (!(fieldValue instanceof Number number)) {
            throw new TradingRequestValidationException(fieldName + " must be numeric or null");
        }
        BigDecimal value = TradingPricingSupport.scaleAmount(new BigDecimal(number.toString()));
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new TradingRequestValidationException(fieldName + " must be greater than zero");
        }
        return new FieldValue(true, value);
    }

    private record FieldValue(boolean provided, BigDecimal value) {
    }
}
