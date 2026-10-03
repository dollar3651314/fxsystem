package com.falconx.trading.client;

import com.falconx.common.api.ApiResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * STAGE-6-KYC trigger 2：wallet deposit-from-addresses HTTP 实现。
 *
 * <p>调用 {@code GET /internal/v1/wallet/console/deposits/from-addresses?userId=&chain=}，
 * 返回 ApiResponse&lt;{addresses:[..]}&gt;。
 */
@Component
public class HttpWithdrawDepositAddressQueryClient implements WithdrawDepositAddressQueryClient {

    private static final Logger log = LoggerFactory.getLogger(HttpWithdrawDepositAddressQueryClient.class);

    private static final ParameterizedTypeReference<ApiResponse<FromAddressesInternalResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final TradingExternalRpcClient rpcClient;

    public HttpWithdrawDepositAddressQueryClient(TradingExternalRpcClient rpcClient) {
        this.rpcClient = rpcClient;
    }

    @Override
    public List<String> listConfirmedFromAddresses(long userId, String chain) {
        String path = UriComponentsBuilder.fromUriString("/internal/v1/wallet/console/deposits/from-addresses")
                .queryParam("userId", userId)
                .queryParam("chain", chain)
                .build().toUriString();
        FromAddressesInternalResponse response = rpcClient.get(path, RESPONSE_TYPE);
        if (response == null || response.addresses() == null) {
            log.warn("trading.withdraw.deposit-addresses.empty userId={} chain={}", userId, chain);
            return List.of();
        }
        return response.addresses();
    }

    /** 与 wallet {@code AdminInternalWalletDepositController.FromAddressesResponse} 一致。 */
    record FromAddressesInternalResponse(List<String> addresses) {}
}
