package com.falconx.trading.client;

import com.falconx.common.api.ApiResponse;
import com.falconx.trading.error.TradingExternalRpcException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * STAGE-7-WITHDRAW：wallet 白名单查询 HTTP 实现。
 *
 * <p>调用 {@code GET /internal/v1/wallet/withdraw/whitelists/{id}}；
 * 下游 wallet 返回错误码 {@code 20020 WITHDRAW_WHITELIST_NOT_FOUND} 时映射为
 * {@code Optional.empty()}（视作"白名单不存在"）。
 */
@Component
public class HttpWithdrawWhitelistQueryClient implements WithdrawWhitelistQueryClient {

    private static final Logger log = LoggerFactory.getLogger(HttpWithdrawWhitelistQueryClient.class);

    /** wallet 端 WalletErrorCode.WITHDRAW_WHITELIST_NOT_FOUND。 */
    private static final String DOWNSTREAM_WHITELIST_NOT_FOUND = "20020";

    private static final ParameterizedTypeReference<ApiResponse<WhitelistInternalResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<WhitelistListInternalResponse>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final TradingExternalRpcClient rpcClient;

    public HttpWithdrawWhitelistQueryClient(TradingExternalRpcClient rpcClient) {
        this.rpcClient = rpcClient;
    }

    @Override
    public Optional<WhitelistView> findById(long whitelistId) {
        String path = "/internal/v1/wallet/withdraw/whitelists/" + whitelistId;
        try {
            WhitelistInternalResponse response = rpcClient.get(path, RESPONSE_TYPE);
            return response == null ? Optional.empty() : Optional.of(toView(response));
        } catch (TradingExternalRpcException ex) {
            if (DOWNSTREAM_WHITELIST_NOT_FOUND.equals(ex.getDownstreamCode())) {
                log.info("trading.withdraw.whitelist.not-found id={}", whitelistId);
                return Optional.empty();
            }
            throw ex;
        }
    }

    @Override
    public List<WhitelistView> listByUser(long userId) {
        String path = UriComponentsBuilder.fromUriString("/internal/v1/wallet/withdraw/whitelists")
                .queryParam("userId", userId)
                .build().toUriString();
        WhitelistListInternalResponse response = rpcClient.get(path, LIST_TYPE);
        if (response == null || response.items() == null) {
            return List.of();
        }
        return response.items().stream()
                .map(HttpWithdrawWhitelistQueryClient::toView)
                .toList();
    }

    @Override
    public WhitelistView add(long userId, String network, String address, String label) {
        String path = UriComponentsBuilder.fromUriString("/internal/v1/wallet/withdraw/whitelists")
                .queryParam("userId", userId)
                .build().toUriString();
        Map<String, Object> body = Map.of(
                "network", network,
                "address", address,
                "label", label == null ? "" : label
        );
        WhitelistInternalResponse response = rpcClient.post(path, body, RESPONSE_TYPE);
        return toView(response);
    }

    @Override
    public WhitelistView delete(long userId, long whitelistId) {
        String path = UriComponentsBuilder.fromUriString("/internal/v1/wallet/withdraw/whitelists/" + whitelistId)
                .queryParam("userId", userId)
                .build().toUriString();
        WhitelistInternalResponse response = rpcClient.delete(path, RESPONSE_TYPE);
        return toView(response);
    }

    private static WhitelistView toView(WhitelistInternalResponse response) {
        return new WhitelistView(
                Long.parseLong(response.id()),
                Long.parseLong(response.userId()),
                response.network(),
                response.address(),
                response.label(),
                response.status(),
                response.activatedAt(),
                response.createdAt()
        );
    }

    /**
     * 与 wallet {@code AdminInternalWalletWithdrawController.WhitelistInternalResponse} 一致。
     */
    record WhitelistInternalResponse(
            String id,
            String userId,
            String network,
            String address,
            String label,
            String status,
            OffsetDateTime activatedAt,
            OffsetDateTime createdAt
    ) {}

    /**
     * 与 wallet {@code AdminInternalWalletWithdrawController.WhitelistListResponse} 一致。
     */
    record WhitelistListInternalResponse(List<WhitelistInternalResponse> items) {}
}
