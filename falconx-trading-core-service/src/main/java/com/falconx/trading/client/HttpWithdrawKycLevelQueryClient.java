package com.falconx.trading.client;

import com.falconx.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

/**
 * STAGE-7-WITHDRAW：identity kyc-status HTTP 实现。
 *
 * <p>调用 {@code GET /internal/v1/identity/users/{userId}/kyc-status}，
 * 返回 ApiResponse&lt;KycStatusInternalResponse&gt;。
 */
@Component
public class HttpWithdrawKycLevelQueryClient implements WithdrawKycLevelQueryClient {

    private static final Logger log = LoggerFactory.getLogger(HttpWithdrawKycLevelQueryClient.class);

    private static final ParameterizedTypeReference<ApiResponse<KycStatusInternalResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final TradingExternalRpcClient rpcClient;

    public HttpWithdrawKycLevelQueryClient(TradingExternalRpcClient rpcClient) {
        this.rpcClient = rpcClient;
    }

    @Override
    public int queryKycLevel(long userId) {
        String path = "/internal/v1/identity/users/" + userId + "/kyc-status";
        KycStatusInternalResponse response = rpcClient.get(path, RESPONSE_TYPE);
        if (response == null) {
            log.warn("trading.withdraw.kyc-level.empty-response userId={}", userId);
            return 0;
        }
        return response.kycLevel();
    }

    /**
     * 与 identity {@code com.falconx.identity.api.KycStatusResponse} 一致；本地复用避免引入 contract 反向依赖。
     */
    record KycStatusInternalResponse(long userId, int kycLevel) {}
}
