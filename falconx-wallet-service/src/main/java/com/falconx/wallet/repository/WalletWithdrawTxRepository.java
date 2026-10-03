package com.falconx.wallet.repository;

import com.falconx.wallet.entity.WalletWithdrawTx;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * STAGE-7-WITHDRAW Phase 3：链上出金交易仓储。
 */
public interface WalletWithdrawTxRepository {

    void insert(WalletWithdrawTx tx);

    Optional<WalletWithdrawTx> findById(Long id);

    Optional<WalletWithdrawTx> findByWithdrawOrderId(Long withdrawOrderId);

    List<WalletWithdrawTx> findBroadcastedBefore(OffsetDateTime threshold, int limit);

    /** SIGNING(0) → BROADCAST(1)：写 tx_hash + gas_price + broadcast_at。 */
    int markBroadcastAtomic(Long id, String txHash, BigDecimal gasPrice, OffsetDateTime broadcastAt);

    /** BROADCAST(1) → CONFIRMED(2)：写 block_number + confirmations + gas_used + gas_fee_usd + confirmed_at。 */
    int markConfirmedAtomic(Long id, long blockNumber, int confirmations,
                             BigDecimal gasUsed, BigDecimal gasFeeUsd, OffsetDateTime confirmedAt);

    /** 任意非终态 → FAILED(3)。 */
    int markFailedAtomic(Long id, String failureCode, String failureReason, OffsetDateTime failedAt);

    /** 仅更新 BROADCAST 中的 confirmations（未达 min_confirmations 时使用）。 */
    int updateConfirmations(Long id, int confirmations);
}
