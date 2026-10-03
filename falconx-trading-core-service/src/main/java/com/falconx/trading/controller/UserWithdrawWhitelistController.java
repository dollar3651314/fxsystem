package com.falconx.trading.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.application.WithdrawWhitelistApplicationService;
import com.falconx.trading.client.WithdrawWhitelistQueryClient;
import com.falconx.trading.dto.AddWhitelistRequest;
import com.falconx.trading.dto.WithdrawWhitelistResponse;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-7-WITHDRAW Phase 2：客户端出金白名单端点。
 *
 * <p>详细契约见 docs/api/REST接口规范.md §9.2.6。本控制器只承担 HTTP 入参绑定与响应转换；
 * 业务规则、跨服务调用与错误码翻译统一在 {@link WithdrawWhitelistApplicationService}。
 */
@RestController
@RequestMapping("/api/v1/me/withdraw/whitelist")
public class UserWithdrawWhitelistController {

    private static final Logger log = LoggerFactory.getLogger(UserWithdrawWhitelistController.class);

    private final WithdrawWhitelistApplicationService applicationService;

    public UserWithdrawWhitelistController(WithdrawWhitelistApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping
    public ApiResponse<List<WithdrawWhitelistResponse>> list(@RequestHeader("X-User-Id") long userId) {
        log.info("trading.http.withdraw.whitelist.list.received userId={}", userId);
        List<WithdrawWhitelistResponse> items = applicationService.list(userId).stream()
                .map(UserWithdrawWhitelistController::toResponse)
                .toList();
        return success(items);
    }

    @PostMapping
    public ApiResponse<WithdrawWhitelistResponse> add(@RequestHeader("X-User-Id") long userId,
                                                       @Valid @RequestBody AddWhitelistRequest request) {
        log.info("trading.http.withdraw.whitelist.add.received userId={} network={}", userId, request.network());
        WithdrawWhitelistQueryClient.WhitelistView added = applicationService.add(
                userId, request.network(), request.address(), request.label());
        return success(toResponse(added));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<WithdrawWhitelistResponse> delete(@RequestHeader("X-User-Id") long userId,
                                                          @PathVariable long id) {
        log.info("trading.http.withdraw.whitelist.delete.received userId={} id={}", userId, id);
        WithdrawWhitelistQueryClient.WhitelistView removed = applicationService.delete(userId, id);
        return success(toResponse(removed));
    }

    static WithdrawWhitelistResponse toResponse(WithdrawWhitelistQueryClient.WhitelistView view) {
        return new WithdrawWhitelistResponse(
                String.valueOf(view.id()),
                String.valueOf(view.userId()),
                view.network(),
                view.address(),
                view.label(),
                view.status(),
                view.activatedAt(),
                view.createdAt()
        );
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
