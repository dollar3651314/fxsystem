package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.application.TradingAccountSnapshotApplicationService;
import com.falconx.trading.application.TradingPositionMarginApplicationService;
import com.falconx.trading.command.AddIsolatedMarginCommand;
import com.falconx.trading.dto.AddIsolatedMarginRequest;
import com.falconx.trading.dto.AddIsolatedMarginResponse;
import com.falconx.trading.dto.AddIsolatedMarginResult;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
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
 * STAGE-14D1 Task 5：用户级逐仓保证金追加端点（master §7.4
 * {@code POST /api/v1/me/positions/{id}/supplement-margin}）。
 *
 * <p>固定读取 gateway 注入的 {@code X-User-Id}（无 JWT 自解析，照搬 {@code UserMarginModeController}）。
 *
 * <p><b>复用现有业务实现</b>：本端点仅做协议适配（路径/请求/响应），业务全部委托
 * {@link TradingPositionMarginApplicationService#addIsolatedMargin}（与旧端点
 * {@code POST /api/v1/trading/positions/{id}/margin} 共用同一 ApplicationService，不重复逻辑）。
 *
 * <p><b>越权防护</b>：command 携带 {@code X-User-Id}，service 经
 * {@code findByIdAndUserIdForUpdate(positionId, userId)} 按 (positionId, userId) 联合定位持仓，
 * 非本人持仓查不到 → 40004 POSITION_NOT_FOUND，天然防越权。
 *
 * <p><b>错误码</b>（master §7.4）：30086 SUPPLEMENT_AMOUNT_INVALID（金额非正）、
 * 30085 POSITION_NOT_ISOLATED（CROSS 仓追加无意义）、40004 POSITION_NOT_FOUND（不存在/越权）、
 * 40007 POSITION_ALREADY_CLOSED、40001 INSUFFICIENT_MARGIN。
 *
 * <p>旧端点 {@code /api/v1/trading/positions/{id}/margin} 保留兼容（与本端点共用 service）。
 */
@RestController
@RequestMapping("/api/v1/me/positions")
public class UserPositionMarginController {

    private static final Logger log = LoggerFactory.getLogger(UserPositionMarginController.class);

    private final TradingPositionMarginApplicationService tradingPositionMarginApplicationService;
    private final TradingAccountSnapshotApplicationService tradingAccountSnapshotApplicationService;

    public UserPositionMarginController(TradingPositionMarginApplicationService tradingPositionMarginApplicationService,
                                        TradingAccountSnapshotApplicationService tradingAccountSnapshotApplicationService) {
        this.tradingPositionMarginApplicationService = tradingPositionMarginApplicationService;
        this.tradingAccountSnapshotApplicationService = tradingAccountSnapshotApplicationService;
    }

    /**
     * 为当前用户的一笔 OPEN ISOLATED 持仓追加逐仓保证金。
     */
    @PostMapping("/{positionId}/supplement-margin")
    public ApiResponse<AddIsolatedMarginResponse> supplementMargin(@RequestHeader("X-User-Id") Long userId,
                                                                   @PathVariable("positionId") Long positionId,
                                                                   @Valid @RequestBody AddIsolatedMarginRequest request) {
        log.info("trading.http.me.position.supplement-margin.received userId={} positionId={} amount={}",
                userId, positionId, request.amount());
        AddIsolatedMarginResult result = tradingPositionMarginApplicationService.addIsolatedMargin(
                new AddIsolatedMarginCommand(userId, positionId, request.amount()));
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
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
