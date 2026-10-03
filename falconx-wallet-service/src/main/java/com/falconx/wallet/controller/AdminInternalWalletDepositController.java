package com.falconx.wallet.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.wallet.application.WalletDepositAdminApplicationService;
import com.falconx.wallet.dto.AdminWalletDepositListResponse;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAGE-2-DEPOSIT R4：wallet-service 入金记录 admin internal RPC。
 *
 * <p>路径前缀：{@code /internal/v1/wallet/console/deposits}。
 * 鉴权：{@link com.falconx.wallet.security.WalletInternalApiTokenFilter} 校验 X-Internal-Token + X-Admin-User-Id。
 * 契约：[`管理端接口规范`](../../../../../../../../docs/api/管理端接口规范.md) §9。
 */
@RestController
@RequestMapping("/internal/v1/wallet/console")
public class AdminInternalWalletDepositController {

    private static final Logger log = LoggerFactory.getLogger(AdminInternalWalletDepositController.class);

    private final WalletDepositAdminApplicationService depositAdminService;

    public AdminInternalWalletDepositController(WalletDepositAdminApplicationService depositAdminService) {
        this.depositAdminService = depositAdminService;
    }

    @GetMapping("/deposits")
    public ApiResponse<AdminWalletDepositListResponse> listDeposits(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String chain,
            @RequestParam(required = false) String token,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) OffsetDateTime fromDetectedAt,
            @RequestParam(required = false) OffsetDateTime toDetectedAt,
            @RequestParam(defaultValue = "false") boolean onlyOrphan,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        log.info("wallet.admin.deposit.list.received userId={} chain={} token={} status={} onlyOrphan={} page={} size={}",
                userId, chain, token, status, onlyOrphan, safePage, safeSize);
        return success(depositAdminService.listDeposits(
                userId, chain, token, status, fromDetectedAt, toDetectedAt, onlyOrphan, safePage, safeSize));
    }

    @GetMapping("/deposits/{id}")
    public ApiResponse<AdminWalletDepositListResponse.Item> getDeposit(@PathVariable long id) {
        log.info("wallet.admin.deposit.detail.received id={}", id);
        return success(depositAdminService.getDepositById(id));
    }

    /**
     * STAGE-6-KYC trigger 2：返回用户在指定链上历史 CONFIRMED 入金的去重 from_address 集合。
     *
     * <p>消费方 trading-core 出金提交时用此判定「toAddress ∉ 该集合 → 拒绝陌生地址 / 触发 KYC」。
     * 鉴权与同 controller 其他 endpoint 一致（X-Internal-Token + X-Admin-User-Id）。
     */
    @GetMapping("/deposits/from-addresses")
    public ApiResponse<FromAddressesResponse> listFromAddresses(@RequestParam long userId,
                                                                 @RequestParam String chain) {
        log.info("wallet.admin.deposit.from-addresses.received userId={} chain={}", userId, chain);
        java.util.List<String> addresses = depositAdminService.listConfirmedFromAddresses(userId, chain);
        return success(new FromAddressesResponse(addresses));
    }

    /** trading-core 反序列化此结构（{ addresses: [..] }）。 */
    public record FromAddressesResponse(java.util.List<String> addresses) {}

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
