package com.falconx.trading.application;

import com.falconx.trading.client.WithdrawWhitelistQueryClient;
import com.falconx.trading.entity.TradingWithdrawNetwork;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.error.TradingExternalRpcException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * STAGE-7-WITHDRAW Phase 2：客户端白名单 CRUD 应用服务（trading-core 透传到 wallet）。
 *
 * <p>本服务承担三件事：
 * <ol>
 *   <li>客户端入参的本地校验（地址格式、network 合法性、label 长度）</li>
 *   <li>透传 wallet internal RPC（{@link WithdrawWhitelistQueryClient}）</li>
 *   <li>把 wallet 错误码翻译为客户端面向的 30051 / 30052 / 30053</li>
 * </ol>
 *
 * <p>错误码翻译对照：
 * <ul>
 *   <li>wallet 20020 NOT_FOUND → trading 30053 WITHDRAW_WHITELIST_NOT_FOUND</li>
 *   <li>wallet 20021 LIMIT_EXCEEDED → trading 30051 WITHDRAW_WHITELIST_LIMIT_EXCEEDED</li>
 *   <li>wallet 20022 DUPLICATE → trading 30052 WITHDRAW_WHITELIST_DUPLICATE</li>
 * </ul>
 */
@Service
public class WithdrawWhitelistApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawWhitelistApplicationService.class);

    private static final Pattern ERC20_ADDRESS_PATTERN = Pattern.compile("^0x[0-9a-fA-F]{40}$");
    private static final Pattern TRC20_ADDRESS_PATTERN = Pattern.compile("^T[1-9A-HJ-NP-Za-km-z]{33}$");

    private final WithdrawWhitelistQueryClient whitelistClient;

    public WithdrawWhitelistApplicationService(WithdrawWhitelistQueryClient whitelistClient) {
        this.whitelistClient = whitelistClient;
    }

    /**
     * 列出用户白名单（含 PENDING + ACTIVE，按 wallet 端排序）。
     */
    public List<WithdrawWhitelistQueryClient.WhitelistView> list(long userId) {
        return whitelistClient.listByUser(userId);
    }

    /**
     * 新增白名单。本地校验失败抛 30044/30045；wallet 业务错误翻译为 30051/30052。
     */
    public WithdrawWhitelistQueryClient.WhitelistView add(long userId, String networkRaw, String address, String label) {
        TradingWithdrawNetwork network = TradingWithdrawNetwork.fromValue(networkRaw);
        if (network == null) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_NETWORK_UNSUPPORTED,
                    Map.of("network", networkRaw));
        }
        String normalized = address == null ? "" : address.trim();
        if (!matchesAddressPattern(network, normalized)) {
            throw new TradingBusinessException(TradingErrorCode.WITHDRAW_ADDRESS_INVALID,
                    Map.of("network", network.name()));
        }
        try {
            return whitelistClient.add(userId, network.name(), normalized, label);
        } catch (TradingExternalRpcException ex) {
            translateWalletErrorCode(ex);
            throw ex;
        }
    }

    /**
     * 删除白名单。wallet 20020 NOT_FOUND 翻译为 30053。
     */
    public WithdrawWhitelistQueryClient.WhitelistView delete(long userId, long whitelistId) {
        try {
            return whitelistClient.delete(userId, whitelistId);
        } catch (TradingExternalRpcException ex) {
            translateWalletErrorCode(ex);
            throw ex;
        }
    }

    private static void translateWalletErrorCode(TradingExternalRpcException ex) {
        String downstream = ex.getDownstreamCode();
        if (downstream == null) return;
        switch (downstream) {
            case "20020" -> throw new TradingBusinessException(TradingErrorCode.WITHDRAW_WHITELIST_NOT_FOUND);
            case "20021" -> throw new TradingBusinessException(TradingErrorCode.WITHDRAW_WHITELIST_LIMIT_EXCEEDED);
            case "20022" -> throw new TradingBusinessException(TradingErrorCode.WITHDRAW_WHITELIST_DUPLICATE);
            default -> {
                // 其他下游错误保持透传
            }
        }
    }

    private static boolean matchesAddressPattern(TradingWithdrawNetwork network, String address) {
        if (address == null || address.isEmpty()) return false;
        return switch (network) {
            case ERC20 -> ERC20_ADDRESS_PATTERN.matcher(address).matches();
            case TRC20 -> TRC20_ADDRESS_PATTERN.matcher(address).matches();
        };
    }
}
