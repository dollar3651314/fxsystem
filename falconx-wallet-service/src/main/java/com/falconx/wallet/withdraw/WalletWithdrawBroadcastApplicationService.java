package com.falconx.wallet.withdraw;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.contract.event.TradingWithdrawReviewedEventPayload;
import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.contract.event.WalletWithdrawBroadcastedEventPayload;
import com.falconx.wallet.contract.event.WalletWithdrawFailedEventPayload;
import com.falconx.wallet.entity.WalletWithdrawTx;
import com.falconx.wallet.entity.WalletWithdrawTxStatus;
import com.falconx.wallet.error.WalletErrorCode;
import com.falconx.wallet.kms.KmsSigner;
import com.falconx.wallet.kms.KmsSignerException;
import com.falconx.wallet.producer.WalletEventPublisher;
import com.falconx.wallet.repository.WalletWithdrawTxRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.utils.Numeric;

/**
 * STAGE-7-WITHDRAW Phase 3：出金链上签名 / 广播 / 发布 broadcast 事件。
 *
 * <p>触发：{@code TradingWithdrawReviewedEventConsumer} 消费 {@code trading.withdraw.reviewed}
 * result=APPROVED 时调用 {@link #broadcast}。
 *
 * <p>幂等：t_withdraw_tx 唯一约束 {@code uk_withdraw_order(withdraw_order_id)}；重复触发
 * DuplicateKeyException 时直接跳过。
 *
 * <p>失败语义：
 * <ul>
 *   <li>{@link KmsSignerException}（含 Stub 抛 {@link UnsupportedOperationException}）→ 不写
 *       t_withdraw_tx；发布 {@code wallet.withdraw.failed} failureCode=20014</li>
 *   <li>{@link IOException} / web3j 返回 error → 已写 SIGNING tx → markFailedAtomic +
 *       发布 failed failureCode=20010</li>
 *   <li>未知 RuntimeException → 发布 failed failureCode=20010 兜底，避免单笔卡在 SIGNING</li>
 * </ul>
 */
@Component
public class WalletWithdrawBroadcastApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WalletWithdrawBroadcastApplicationService.class);
    private static final BigInteger GWEI = BigInteger.valueOf(1_000_000_000L);

    private final WalletServiceProperties properties;
    private final WalletWithdrawTxRepository repository;
    private final WalletEventPublisher eventPublisher;
    private final KmsSigner kmsSigner;
    private final Web3j web3j;
    private final EthNonceManager nonceManager;
    private final IdGenerator idGenerator;

    public WalletWithdrawBroadcastApplicationService(WalletServiceProperties properties,
                                                      WalletWithdrawTxRepository repository,
                                                      WalletEventPublisher eventPublisher,
                                                      KmsSigner kmsSigner,
                                                      @Qualifier("walletWithdrawWeb3j") Web3j web3j,
                                                      EthNonceManager nonceManager,
                                                      IdGenerator idGenerator) {
        this.properties = properties;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.kmsSigner = kmsSigner;
        this.web3j = web3j;
        this.nonceManager = nonceManager;
        this.idGenerator = idGenerator;
    }

    /**
     * 处理一条 APPROVED reviewed 事件 → 链上广播 USDT transfer。
     */
    public void broadcast(TradingWithdrawReviewedEventPayload reviewed) {
        if (reviewed == null || !"APPROVED".equals(reviewed.result())) {
            return;
        }
        if (!"ERC20".equals(reviewed.network())) {
            log.info("wallet.withdraw.broadcast.skipped.non-erc20 withdrawId={} network={}",
                    reviewed.withdrawId(), reviewed.network());
            return;
        }
        if (repository.findByWithdrawOrderId(reviewed.withdrawId()).isPresent()) {
            log.info("wallet.withdraw.broadcast.skipped.duplicate withdrawId={}", reviewed.withdrawId());
            return;
        }

        WalletServiceProperties.EthWithdraw cfg = properties.getWithdraw().getEth();
        WalletServiceProperties.SigningKey signing = properties.getKms().getErc20();
        String fromAddress = signing.getFromAddress();
        if (fromAddress == null || fromAddress.isBlank()) {
            publishFailNoTx(reviewed, WalletErrorCode.WITHDRAW_SIGNER_UNAVAILABLE.code(),
                    "ERC20 fromAddress not configured");
            return;
        }
        Erc20ContractConfig tokenContract = resolveErc20Contract(reviewed, cfg);
        if (tokenContract == null) {
            publishFailNoTx(reviewed, WalletErrorCode.WITHDRAW_BROADCAST_FAILED.code(),
                    "ERC20 token contract not configured for currency=" + reviewed.currency());
            return;
        }

        BigInteger amountWei = scaleToTokenUnits(reviewed.amount(), tokenContract.decimals());
        String transferData = encodeErc20Transfer(reviewed.targetAddress(), amountWei);

        long nonce;
        BigInteger gasPrice;
        try {
            gasPrice = fetchGasPriceCapped(cfg);
            nonce = nonceManager.nextNonce(fromAddress);
        } catch (RuntimeException ex) {
            publishFailNoTx(reviewed, WalletErrorCode.WITHDRAW_BROADCAST_FAILED.code(),
                    "Failed to prepare nonce/gas: " + ex.getMessage());
            return;
        }

        RawTransaction rawTx = RawTransaction.createTransaction(
                BigInteger.valueOf(nonce),
                gasPrice,
                BigInteger.valueOf(cfg.getGasLimit()),
                tokenContract.contractAddress(),
                BigInteger.ZERO,
                transferData
        );

        Long txId = idGenerator.nextId();
        OffsetDateTime now = OffsetDateTime.now();
        WalletWithdrawTx signingTx = new WalletWithdrawTx(
                txId, reviewed.withdrawId(), reviewed.userId(), reviewed.network(),
                fromAddress, reviewed.targetAddress(), reviewed.amount(),
                nonce, gasPrice == null ? null : new BigDecimal(gasPrice),
                null, null, null, null, 0,
                WalletWithdrawTxStatus.SIGNING, null, null,
                null, null, null, now, now
        );
        try {
            repository.insert(signingTx);
        } catch (DuplicateKeyException dupe) {
            log.info("wallet.withdraw.broadcast.skipped.unique-violation withdrawId={}", reviewed.withdrawId());
            return;
        }

        byte[] signedTxBytes;
        try {
            signedTxBytes = signTransaction(rawTx, cfg.getChainId(), reviewed.network(), fromAddress);
        } catch (KmsSignerException | UnsupportedOperationException ex) {
            log.warn("wallet.withdraw.broadcast.signer-unavailable withdrawId={} cause={}",
                    reviewed.withdrawId(), ex.toString());
            repository.markFailedAtomic(txId,
                    WalletErrorCode.WITHDRAW_SIGNER_UNAVAILABLE.code(),
                    truncate(ex.getMessage(), 512),
                    OffsetDateTime.now());
            eventPublisher.publishWithdrawFailed(new WalletWithdrawFailedEventPayload(
                    reviewed.withdrawId(), reviewed.userId(), reviewed.network(), null,
                    WalletErrorCode.WITHDRAW_SIGNER_UNAVAILABLE.code(),
                    truncate(ex.getMessage(), 512),
                    OffsetDateTime.now()));
            return;
        }

        String txHash;
        try {
            EthSendTransaction resp = web3j.ethSendRawTransaction(Numeric.toHexString(signedTxBytes)).send();
            if (resp.hasError()) {
                throw new IOException("eth_sendRawTransaction error: " + resp.getError().getMessage());
            }
            txHash = resp.getTransactionHash();
        } catch (IOException | RuntimeException ex) {
            log.warn("wallet.withdraw.broadcast.send-failed withdrawId={} cause={}",
                    reviewed.withdrawId(), ex.toString());
            repository.markFailedAtomic(txId,
                    WalletErrorCode.WITHDRAW_BROADCAST_FAILED.code(),
                    truncate(ex.getMessage(), 512),
                    OffsetDateTime.now());
            eventPublisher.publishWithdrawFailed(new WalletWithdrawFailedEventPayload(
                    reviewed.withdrawId(), reviewed.userId(), reviewed.network(), null,
                    WalletErrorCode.WITHDRAW_BROADCAST_FAILED.code(),
                    truncate(ex.getMessage(), 512),
                    OffsetDateTime.now()));
            return;
        }

        OffsetDateTime broadcastAt = OffsetDateTime.now();
        repository.markBroadcastAtomic(txId, txHash, new BigDecimal(gasPrice), broadcastAt);
        BigDecimal gasFeeUsd = estimateGasFeeUsd(gasPrice, cfg.getGasLimit());
        eventPublisher.publishWithdrawBroadcasted(new WalletWithdrawBroadcastedEventPayload(
                reviewed.withdrawId(), reviewed.userId(), reviewed.network(),
                txHash, nonce, gasFeeUsd, broadcastAt));
        log.info("wallet.withdraw.broadcast.completed withdrawId={} txHash={} nonce={} gasPriceGwei={}",
                reviewed.withdrawId(), txHash, nonce, gasPrice.divide(GWEI));
    }

    private byte[] signTransaction(RawTransaction rawTx, long chainId, String network, String fromAddress) {
        byte[] encodedTx = TransactionEncoder.encode(rawTx, chainId);
        byte[] hash = Hash.sha3(encodedTx);
        byte[] signature = kmsSigner.sign(network, fromAddress, hash);
        if (signature == null || signature.length != 65) {
            throw new KmsSignerException("KmsSigner returned invalid signature length: "
                    + (signature == null ? "null" : signature.length));
        }
        byte[] r = new byte[32];
        byte[] s = new byte[32];
        byte[] v = new byte[]{signature[64]};
        System.arraycopy(signature, 0, r, 0, 32);
        System.arraycopy(signature, 32, s, 0, 32);
        Sign.SignatureData sigData = TransactionEncoder.createEip155SignatureData(
                new Sign.SignatureData(v, r, s), chainId);
        return encodeSignedTx(rawTx, sigData);
    }

    private static byte[] encodeSignedTx(RawTransaction rawTx, Sign.SignatureData sigData) {
        List<org.web3j.rlp.RlpType> values = TransactionEncoder.asRlpValues(rawTx, sigData);
        org.web3j.rlp.RlpList rlpList = new org.web3j.rlp.RlpList(values);
        return org.web3j.rlp.RlpEncoder.encode(rlpList);
    }

    private BigInteger fetchGasPriceCapped(WalletServiceProperties.EthWithdraw cfg) {
        try {
            EthGasPrice resp = web3j.ethGasPrice().send();
            if (resp.hasError()) {
                throw new IllegalStateException("eth_gasPrice error: " + resp.getError().getMessage());
            }
            BigInteger gas = resp.getGasPrice();
            BigInteger cap = BigInteger.valueOf(cfg.getGasPriceMaxGwei()).multiply(GWEI);
            return gas.compareTo(cap) > 0 ? cap : gas;
        } catch (IOException ex) {
            throw new IllegalStateException("eth_gasPrice IO error", ex);
        }
    }

    private static Erc20ContractConfig resolveErc20Contract(TradingWithdrawReviewedEventPayload reviewed,
                                                            WalletServiceProperties.EthWithdraw cfg) {
        String token = reviewed.currency() == null ? "" : reviewed.currency().trim().toUpperCase(Locale.ROOT);
        return switch (token) {
            case "USDT" -> hasText(cfg.getUsdtContract())
                    ? new Erc20ContractConfig(cfg.getUsdtContract(), cfg.getUsdtDecimals())
                    : null;
            case "USDC" -> hasText(cfg.getUsdcContract())
                    ? new Erc20ContractConfig(cfg.getUsdcContract(), cfg.getUsdcDecimals())
                    : null;
            default -> null;
        };
    }

    private static String encodeErc20Transfer(String to, BigInteger valueWei) {
        Function fn = new Function(
                "transfer",
                List.of(new Address(to), new Uint256(valueWei)),
                Collections.emptyList()
        );
        return FunctionEncoder.encode(fn);
    }

    private static BigInteger scaleToTokenUnits(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals).toBigInteger();
    }

    private static BigDecimal estimateGasFeeUsd(BigInteger gasPrice, long gasLimit) {
        // 一期占位：ETH 价格未接入；gasPrice * gasLimit 给 wei 计价，转换成 0 USD 占位避免误导。
        // B 段真链 E2E 接入后由 confirmation scheduler 用 receipt.gasUsed * gasPrice * ethUsd 重算。
        return BigDecimal.ZERO;
    }

    private void publishFailNoTx(TradingWithdrawReviewedEventPayload reviewed, String code, String reason) {
        eventPublisher.publishWithdrawFailed(new WalletWithdrawFailedEventPayload(
                reviewed.withdrawId(), reviewed.userId(), reviewed.network(), null,
                code, truncate(reason, 512), OffsetDateTime.now()));
        log.warn("wallet.withdraw.broadcast.failed-no-tx withdrawId={} code={} reason={}",
                reviewed.withdrawId(), code, reason);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Erc20ContractConfig(String contractAddress, int decimals) {
    }
}
