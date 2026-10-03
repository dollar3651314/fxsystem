package com.falconx.wallet.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.wallet.application.WalletWithdrawWhitelistApplicationService;
import com.falconx.wallet.entity.WalletWithdrawWhitelist;
import com.falconx.wallet.error.WalletBusinessException;
import com.falconx.wallet.error.WalletErrorCode;
import com.falconx.wallet.repository.WalletWithdrawWhitelistRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-7-WITHDRAW Phase 1：wallet-service 出金白名单 internal RPC。
 *
 * <p>路径前缀：{@code /internal/v1/wallet/withdraw}。
 * 鉴权：{@link com.falconx.wallet.security.WalletInternalApiTokenFilter} 校验
 * X-Internal-Token + X-Admin-User-Id（service-to-service 调用方传服务标识值即可）。
 *
 * <p>当前仅暴露白名单读取，供 trading-core 出金前置校验。后续阶段补 ACTIVE 切换调度器与
 * 用户级 CRUD 透传端点。
 */
@RestController
@RequestMapping("/internal/v1/wallet/withdraw")
public class AdminInternalWalletWithdrawController {

    private static final Logger log = LoggerFactory.getLogger(AdminInternalWalletWithdrawController.class);

    private final WalletWithdrawWhitelistRepository whitelistRepository;
    private final WalletWithdrawWhitelistApplicationService whitelistApplicationService;

    public AdminInternalWalletWithdrawController(WalletWithdrawWhitelistRepository whitelistRepository,
                                                  WalletWithdrawWhitelistApplicationService whitelistApplicationService) {
        this.whitelistRepository = whitelistRepository;
        this.whitelistApplicationService = whitelistApplicationService;
    }

    /**
     * 按 ID 查询白名单。返回所有 owner 字段供 trading-core 自行做 userId / address / network /
     * status 比对（trading-core 出金前置校验需要做完整的等价匹配）。
     */
    @GetMapping("/whitelists/{id}")
    public ApiResponse<WhitelistInternalResponse> getById(@PathVariable long id,
                                                           @RequestHeader("X-Admin-User-Id") long callerId) {
        log.info("wallet.internal.withdraw.whitelist.get.received id={} callerId={}", id, callerId);
        WalletWithdrawWhitelist whitelist = whitelistRepository.findById(id)
                .orElseThrow(() -> new WalletBusinessException(WalletErrorCode.WITHDRAW_WHITELIST_NOT_FOUND));
        return success(toResponse(whitelist));
    }

    /**
     * 用户白名单列表（非 REMOVED），按 created_at DESC, id DESC 排序。
     */
    @GetMapping("/whitelists")
    public ApiResponse<WhitelistListResponse> listByUser(@RequestParam long userId,
                                                          @RequestHeader("X-Admin-User-Id") long callerId) {
        log.info("wallet.internal.withdraw.whitelist.list.received userId={} callerId={}", userId, callerId);
        List<WhitelistInternalResponse> items = whitelistApplicationService.list(userId)
                .stream().map(AdminInternalWalletWithdrawController::toResponse).toList();
        return success(new WhitelistListResponse(items));
    }

    /**
     * 新增白名单。返回 status=PENDING 的新记录（24h 后由调度器切到 ACTIVE）。
     */
    @PostMapping("/whitelists")
    public ApiResponse<WhitelistInternalResponse> add(@RequestParam long userId,
                                                       @Valid @RequestBody AddWhitelistRequest request,
                                                       @RequestHeader("X-Admin-User-Id") long callerId) {
        log.info("wallet.internal.withdraw.whitelist.add.received userId={} network={} callerId={}",
                userId, request.network(), callerId);
        WalletWithdrawWhitelist added = whitelistApplicationService.add(
                userId, request.network(), request.address(), request.label());
        return success(toResponse(added));
    }

    /**
     * 删除白名单。返回 status=REMOVED 的对象。
     */
    @DeleteMapping("/whitelists/{id}")
    public ApiResponse<WhitelistInternalResponse> delete(@PathVariable long id,
                                                          @RequestParam long userId,
                                                          @RequestHeader("X-Admin-User-Id") long callerId) {
        log.info("wallet.internal.withdraw.whitelist.delete.received id={} userId={} callerId={}",
                id, userId, callerId);
        WalletWithdrawWhitelist removed = whitelistApplicationService.delete(userId, id);
        return success(toResponse(removed));
    }

    public record AddWhitelistRequest(
            @NotBlank @Size(max = 16) String network,
            @NotBlank @Size(max = 128) String address,
            @Size(max = 64) String label
    ) {}

    public record WhitelistListResponse(List<WhitelistInternalResponse> items) {}

    private static WhitelistInternalResponse toResponse(WalletWithdrawWhitelist whitelist) {
        return new WhitelistInternalResponse(
                String.valueOf(whitelist.id()),
                String.valueOf(whitelist.userId()),
                whitelist.network(),
                whitelist.address(),
                whitelist.label(),
                whitelist.status().name(),
                whitelist.activatedAt(),
                whitelist.createdAt()
        );
    }

    private <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(),
                MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }

    /**
     * 白名单 internal 响应。字段类型与 wallet owner 一致；trading-core 不需要反序列化为
     * wallet 内部枚举，直接按字符串比较即可。
     */
    public record WhitelistInternalResponse(
            String id,
            String userId,
            String network,
            String address,
            String label,
            String status,
            OffsetDateTime activatedAt,
            OffsetDateTime createdAt
    ) {}
}
