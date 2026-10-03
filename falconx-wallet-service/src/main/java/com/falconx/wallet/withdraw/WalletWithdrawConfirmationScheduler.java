package com.falconx.wallet.withdraw;

import com.falconx.wallet.config.WalletServiceProperties;
import com.falconx.wallet.contract.event.WalletWithdrawConfirmedEventPayload;
import com.falconx.wallet.contract.event.WalletWithdrawFailedEventPayload;
import com.falconx.wallet.entity.WalletWithdrawTx;
import com.falconx.wallet.error.WalletErrorCode;
import com.falconx.wallet.producer.WalletEventPublisher;
import com.falconx.wallet.repository.WalletWithdrawTxRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

/**
 * STAGE-7-WITHDRAW Phase 3：链上确认调度器。
 *
 * <p>周期扫 {@code t_withdraw_tx WHERE status=BROADCAST}，调 {@code eth_getTransactionReceipt}：
 * <ul>
 *   <li>receipt 不可用 → 跳过，下个 tick 重试</li>
 *   <li>receipt.status=0 → markFailedAtomic（20011 TX_REVERTED）+ 发布 wallet.withdraw.failed</li>
 *   <li>confirmations &lt; min → updateConfirmations(count)</li>
 *   <li>confirmations ≥ min → markConfirmedAtomic + 发布 wallet.withdraw.confirmed</li>
 * </ul>
 *
 * <p>注：本 scheduler 通过外部 {@code @Scheduled(fixedDelayString)} 注解触发，间隔由
 * {@code falconx.wallet.withdraw.eth.confirmation-scheduler-interval} 控制（默认 30s）。
 */
@Component
public class WalletWithdrawConfirmationScheduler {

    private static final Logger log = LoggerFactory.getLogger(WalletWithdrawConfirmationScheduler.class);
    private static final int SCAN_LIMIT = 50;

    private final WalletServiceProperties properties;
    private final WalletWithdrawTxRepository repository;
    private final WalletEventPublisher eventPublisher;
    private final Web3j web3j;

    public WalletWithdrawConfirmationScheduler(WalletServiceProperties properties,
                                                WalletWithdrawTxRepository repository,
                                                WalletEventPublisher eventPublisher,
                                                @Qualifier("walletWithdrawWeb3j") Web3j web3j) {
        this.properties = properties;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.web3j = web3j;
    }

    @Scheduled(fixedDelayString = "${falconx.wallet.withdraw.eth.confirmation-scheduler-interval:30s}")
    public void tick() {
        List<WalletWithdrawTx> txs = repository.findBroadcastedBefore(OffsetDateTime.now(), SCAN_LIMIT);
        if (txs.isEmpty()) {
            return;
        }
        long currentBlock;
        try {
            EthBlockNumber resp = web3j.ethBlockNumber().send();
            if (resp.hasError()) {
                log.warn("wallet.withdraw.confirm.block-number-error msg={}", resp.getError().getMessage());
                return;
            }
            currentBlock = resp.getBlockNumber().longValueExact();
        } catch (IOException ex) {
            log.warn("wallet.withdraw.confirm.block-number-io-error", ex);
            return;
        }
        for (WalletWithdrawTx tx : txs) {
            processOne(tx, currentBlock);
        }
    }

    private void processOne(WalletWithdrawTx tx, long currentBlock) {
        int minConfirmations = properties.getWithdraw().getEth().getMinConfirmations();
        Optional<TransactionReceipt> receiptOpt;
        try {
            EthGetTransactionReceipt resp = web3j.ethGetTransactionReceipt(tx.txHash()).send();
            if (resp.hasError()) {
                log.warn("wallet.withdraw.confirm.receipt-error withdrawId={} txHash={} msg={}",
                        tx.withdrawOrderId(), tx.txHash(), resp.getError().getMessage());
                return;
            }
            receiptOpt = resp.getTransactionReceipt();
        } catch (IOException ex) {
            log.warn("wallet.withdraw.confirm.receipt-io-error withdrawId={} txHash={}",
                    tx.withdrawOrderId(), tx.txHash(), ex);
            return;
        }
        if (receiptOpt.isEmpty()) {
            return;
        }
        TransactionReceipt receipt = receiptOpt.get();
        if (!receipt.isStatusOK()) {
            handleReverted(tx, receipt);
            return;
        }
        long blockNumber = receipt.getBlockNumber().longValueExact();
        long confirmations = Math.max(0L, currentBlock - blockNumber + 1L);
        if (confirmations < minConfirmations) {
            repository.updateConfirmations(tx.id(), (int) confirmations);
            log.debug("wallet.withdraw.confirm.pending withdrawId={} confirmations={}/{}",
                    tx.withdrawOrderId(), confirmations, minConfirmations);
            return;
        }
        handleConfirmed(tx, receipt, blockNumber, (int) confirmations);
    }

    private void handleConfirmed(WalletWithdrawTx tx, TransactionReceipt receipt, long blockNumber, int confirmations) {
        BigDecimal gasUsed = new BigDecimal(receipt.getGasUsed());
        BigDecimal gasFeeUsd = BigDecimal.ZERO; // 占位，详见 broadcast service estimateGasFeeUsd 注释
        OffsetDateTime confirmedAt = OffsetDateTime.now();
        int updated = repository.markConfirmedAtomic(tx.id(), blockNumber, confirmations, gasUsed, gasFeeUsd, confirmedAt);
        if (updated == 0) {
            log.info("wallet.withdraw.confirm.skipped-cas-mismatch withdrawId={}", tx.withdrawOrderId());
            return;
        }
        eventPublisher.publishWithdrawConfirmed(new WalletWithdrawConfirmedEventPayload(
                tx.withdrawOrderId(), tx.userId(), tx.network(),
                tx.txHash(), blockNumber, confirmations, confirmedAt));
        log.info("wallet.withdraw.confirm.completed withdrawId={} txHash={} blockNumber={} confirmations={}",
                tx.withdrawOrderId(), tx.txHash(), blockNumber, confirmations);
    }

    private void handleReverted(WalletWithdrawTx tx, TransactionReceipt receipt) {
        String reason = "receipt status=" + receipt.getStatus();
        OffsetDateTime failedAt = OffsetDateTime.now();
        int updated = repository.markFailedAtomic(tx.id(),
                WalletErrorCode.WITHDRAW_TX_REVERTED.code(), reason, failedAt);
        if (updated == 0) {
            log.info("wallet.withdraw.confirm.failed-cas-mismatch withdrawId={}", tx.withdrawOrderId());
            return;
        }
        eventPublisher.publishWithdrawFailed(new WalletWithdrawFailedEventPayload(
                tx.withdrawOrderId(), tx.userId(), tx.network(), tx.txHash(),
                WalletErrorCode.WITHDRAW_TX_REVERTED.code(), reason, failedAt));
        log.warn("wallet.withdraw.confirm.tx-reverted withdrawId={} txHash={}",
                tx.withdrawOrderId(), tx.txHash());
    }

    // 公开 BigInteger 包内辅助便于未来 reorg 扩展（保留位）
    @SuppressWarnings("unused")
    private static BigInteger currentBlockAsBigInt(long currentBlock) {
        return BigInteger.valueOf(currentBlock);
    }
}
