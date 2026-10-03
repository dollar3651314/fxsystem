package com.falconx.wallet.listener;

import com.falconx.domain.enums.ChainType;
import com.falconx.wallet.client.WalletBlockchainClientFactory;
import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.util.WalletLogSanitizer;
import java.util.function.Consumer;
import org.p2p.solanaj.rpc.RpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Solanaj 的 Solana 链监听器骨架。
 *
 * <p>该监听器负责把 Solana RPC 客户端接入到 wallet-service，
 * 为后续 slot 轮询、地址过滤和交易解析提供真实 SDK 落点。
 */
public class SolanaRpcChainDepositListener implements ChainDepositListener {

    private static final Logger log = LoggerFactory.getLogger(SolanaRpcChainDepositListener.class);

    private final WalletServiceProperties.Chain chainProperties;
    private final WalletBlockchainClientFactory walletBlockchainClientFactory;

    private RpcClient rpcClient;

    public SolanaRpcChainDepositListener(WalletServiceProperties.Chain chainProperties,
                                         WalletBlockchainClientFactory walletBlockchainClientFactory) {
        this.chainProperties = chainProperties;
        this.walletBlockchainClientFactory = walletBlockchainClientFactory;
    }

    @Override
    public ChainType chainType() {
        return ChainType.SOL;
    }

    @Override
    public void start(Consumer<ObservedDepositTransaction> depositConsumer) {
        if (rpcClient == null) {
            rpcClient = walletBlockchainClientFactory.createSolanaClient(chainProperties.getRpcUrl());
        }
        log.info("wallet.listener.started chain={} client=solanaj rpcUrl={} requiredConfirmations={} scanInterval={}",
                ChainType.SOL,
                WalletLogSanitizer.maskRpcUrl(chainProperties.getRpcUrl()),
                chainProperties.getRequiredConfirmations(),
                chainProperties.getScanInterval());
    }
}
