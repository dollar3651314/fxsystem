package com.falconx.wallet.health;

import java.math.BigInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.NetVersion;

/**
 * STAGE-11-OBS-RECON §14.5 wallet 端 ETH RPC 可达性健康指标。
 *
 * <p>通过 {@code walletWithdrawWeb3j.netVersion()} 探测 Alchemy RPC 是否可达，
 * 命中后 UP（withDetail chainId），失败 DOWN（withException），用于 k8s readiness
 * 与 canary health-all 命令。
 */
@Component("ethRpc")
public class EthRpcHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(EthRpcHealthIndicator.class);

    private final Web3j web3j;

    public EthRpcHealthIndicator(@Qualifier("walletWithdrawWeb3j") Web3j web3j) {
        this.web3j = web3j;
    }

    @Override
    public Health health() {
        try {
            NetVersion netVersion = web3j.netVersion().send();
            if (netVersion.hasError()) {
                return Health.down()
                        .withDetail("error", netVersion.getError().getMessage())
                        .build();
            }
            String version = netVersion.getNetVersion();
            return Health.up()
                    .withDetail("chainId", parseChainId(version))
                    .withDetail("netVersion", version)
                    .build();
        } catch (Exception ex) {
            log.debug("wallet.health.eth_rpc.unreachable error={}", ex.getMessage());
            return Health.down(ex).build();
        }
    }

    private static String parseChainId(String netVersion) {
        try {
            return new BigInteger(netVersion).toString();
        } catch (NumberFormatException ex) {
            return netVersion;
        }
    }
}
