package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.application.MarginModeSwitchApplicationService;
import com.falconx.trading.dto.MarginModeQueryResult;
import com.falconx.trading.dto.MarginModeSwitchRequest;
import com.falconx.trading.dto.MarginModeSwitchResult;
import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-14D1 Task 3：用户级 margin mode 切换端点（master §7.4）。
 *
 * <p>固定读取 gateway 注入的 {@code X-User-Id}（无 JWT 自解析，照搬 {@code UserWithdrawController}）。
 *
 * <ul>
 *   <li>GET /api/v1/me/margin-mode → 当前 mode + cooling_until + can_switch + blockers</li>
 *   <li>POST /api/v1/me/margin-mode（body {@code {targetMode}}）→ 切换闸门
 *       （30080/30081/30082/30083 + CROSS gate 30088）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/me/margin-mode")
public class UserMarginModeController {

    private static final Logger log = LoggerFactory.getLogger(UserMarginModeController.class);

    private final MarginModeSwitchApplicationService marginModeSwitchApplicationService;

    public UserMarginModeController(MarginModeSwitchApplicationService marginModeSwitchApplicationService) {
        this.marginModeSwitchApplicationService = marginModeSwitchApplicationService;
    }

    /**
     * 查询当前用户 margin mode 与可切换性。
     */
    @GetMapping
    public ApiResponse<MarginModeQueryResult> queryMode(@RequestHeader("X-User-Id") Long userId) {
        log.info("trading.http.margin-mode.query.received userId={}", userId);
        return success(marginModeSwitchApplicationService.queryMode(userId));
    }

    /**
     * 切换当前用户 margin mode。
     */
    @PostMapping
    public ApiResponse<MarginModeSwitchResult> switchMode(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody MarginModeSwitchRequest request) {
        log.info("trading.http.margin-mode.switch.received userId={} targetMode={}",
                userId, request.targetMode());
        TradingMarginMode targetMode = parseMode(request.targetMode());
        return success(marginModeSwitchApplicationService.switchMode(userId, targetMode));
    }

    /** 解析目标模式字符串，非法值映射为 40010 MARGIN_MODE_NOT_SUPPORTED（大小写不敏感）。 */
    private TradingMarginMode parseMode(String raw) {
        try {
            return TradingMarginMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new TradingBusinessException(
                    TradingErrorCode.MARGIN_MODE_NOT_SUPPORTED,
                    Map.of("targetMode", raw == null ? "null" : raw));
        }
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
