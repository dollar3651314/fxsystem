package com.falconx.wallet.controller;

import com.falconx.common.api.ApiResponse;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.wallet.application.WalletAddressAllocationApplicationService;
import com.falconx.wallet.command.EnsureWalletDepositAddressesCommand;
import com.falconx.wallet.dto.EnsureWalletDepositAddressesResponse;
import com.falconx.wallet.dto.WalletDepositAddressItemResponse;
import com.falconx.wallet.entity.WalletAddressAssignment;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 入金地址 HTTP 接口。
 */
@RestController
@RequestMapping("/api/v1/wallet/deposit-addresses")
public class WalletDepositAddressController {

    private static final Logger log = LoggerFactory.getLogger(WalletDepositAddressController.class);

    private final WalletAddressAllocationApplicationService walletAddressAllocationApplicationService;

    public WalletDepositAddressController(WalletAddressAllocationApplicationService walletAddressAllocationApplicationService) {
        this.walletAddressAllocationApplicationService = walletAddressAllocationApplicationService;
    }

    @PostMapping("/ensure")
    public ApiResponse<EnsureWalletDepositAddressesResponse> ensureDepositAddresses(
            @RequestHeader("X-User-Id") Long userId) {
        EnsureWalletDepositAddressesCommand command = new EnsureWalletDepositAddressesCommand(userId);
        log.info("wallet.http.deposit-address.ensure.received userId={}", command.userId());
        List<WalletAddressAssignment> assignments =
                walletAddressAllocationApplicationService.ensureDefaultUsdtDepositAddresses(command.userId());
        EnsureWalletDepositAddressesResponse response = new EnsureWalletDepositAddressesResponse(
                assignments.stream().map(this::toResponseItem).toList()
        );
        return ApiResponse.success(response, MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }

    private WalletDepositAddressItemResponse toResponseItem(WalletAddressAssignment assignment) {
        return new WalletDepositAddressItemResponse(
                assignment.network(),
                assignment.chain().name(),
                assignment.token(),
                assignment.address(),
                assignment.addressIndex(),
                assignment.derivationPath()
        );
    }
}
