package com.falconx.wallet.withdraw;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;

/**
 * STAGE-7-WITHDRAW Phase 3：ETH 链 nonce 管理器（单实例 in-process）。
 *
 * <p>语义：
 * <ul>
 *   <li>启动时按 {@code fromAddress} 维度从 RPC {@code eth_getTransactionCount(pending)} 拉取 nonce</li>
 *   <li>{@code nextNonce} 通过 {@link AtomicLong#getAndIncrement} 保证并发递增唯一性</li>
 *   <li>{@code reset} 在 nonce 冲突（{@code nonce too low}）等异常路径重新拉链</li>
 * </ul>
 *
 * <p>多实例部署时本组件需升级为 Redis 串行化（按 docs/domain/状态机规范.md §7B 注记，
 * 一期 single instance）。
 */
public class EthNonceManager {

    private static final Logger log = LoggerFactory.getLogger(EthNonceManager.class);

    private final Web3j web3j;
    private final ConcurrentHashMap<String, AtomicLong> nonces = new ConcurrentHashMap<>();

    public EthNonceManager(Web3j web3j) {
        this.web3j = web3j;
    }

    /**
     * 取下一个可用 nonce 并占用。
     *
     * @param fromAddress 平台热钱包地址（小写归一化）
     * @return 占用的 nonce
     * @throws IllegalStateException 链上拉取 nonce 失败
     */
    public long nextNonce(String fromAddress) {
        String key = normalize(fromAddress);
        AtomicLong counter = nonces.computeIfAbsent(key, this::fetchInitialNonce);
        long nonce = counter.getAndIncrement();
        log.debug("wallet.nonce.allocated address={} nonce={}", key, nonce);
        return nonce;
    }

    /**
     * 重新从链上拉 nonce（nonce 冲突 / 启动失败重试场景）。
     */
    public void reset(String fromAddress) {
        String key = normalize(fromAddress);
        AtomicLong fresh = fetchInitialNonce(key);
        nonces.put(key, fresh);
        log.warn("wallet.nonce.reset address={} newNonce={}", key, fresh.get());
    }

    private AtomicLong fetchInitialNonce(String fromAddress) {
        try {
            EthGetTransactionCount resp = web3j.ethGetTransactionCount(
                    fromAddress, DefaultBlockParameterName.PENDING).send();
            if (resp.hasError()) {
                throw new IllegalStateException("eth_getTransactionCount error: " + resp.getError().getMessage());
            }
            BigInteger count = resp.getTransactionCount();
            log.info("wallet.nonce.initialized address={} startNonce={}", fromAddress, count);
            return new AtomicLong(count.longValueExact());
        } catch (IOException ex) {
            throw new IllegalStateException("eth_getTransactionCount IO error address=" + fromAddress, ex);
        }
    }

    private static String normalize(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }
}
