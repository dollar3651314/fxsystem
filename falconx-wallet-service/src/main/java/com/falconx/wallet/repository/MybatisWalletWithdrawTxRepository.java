package com.falconx.wallet.repository;

import com.falconx.wallet.entity.WalletWithdrawTx;
import com.falconx.wallet.entity.WalletWithdrawTxStatus;
import com.falconx.wallet.repository.mapper.WalletWithdrawTxMapper;
import com.falconx.wallet.repository.mapper.record.WalletWithdrawTxRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * STAGE-7-WITHDRAW Phase 3：链上出金交易仓储 MyBatis 实现。
 */
@Repository
public class MybatisWalletWithdrawTxRepository implements WalletWithdrawTxRepository {

    private final WalletWithdrawTxMapper mapper;

    public MybatisWalletWithdrawTxRepository(WalletWithdrawTxMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(WalletWithdrawTx tx) {
        WalletWithdrawTxRecord record = new WalletWithdrawTxRecord(
                tx.id(),
                tx.withdrawOrderId(),
                tx.userId(),
                tx.network(),
                tx.fromAddress(),
                tx.targetAddress(),
                tx.amount(),
                tx.nonce(),
                tx.gasPrice(),
                tx.gasUsed(),
                tx.gasFeeUsd(),
                tx.txHash(),
                tx.blockNumber(),
                tx.confirmations(),
                tx.status().code(),
                tx.failureCode(),
                tx.failureReason(),
                WalletMybatisSupport.toLocalDateTime(tx.broadcastAt()),
                WalletMybatisSupport.toLocalDateTime(tx.confirmedAt()),
                WalletMybatisSupport.toLocalDateTime(tx.failedAt()),
                WalletMybatisSupport.toLocalDateTime(tx.createdAt()),
                WalletMybatisSupport.toLocalDateTime(tx.updatedAt())
        );
        mapper.insert(record);
    }

    @Override
    public Optional<WalletWithdrawTx> findById(Long id) {
        return Optional.ofNullable(toDomain(mapper.selectById(id)));
    }

    @Override
    public Optional<WalletWithdrawTx> findByWithdrawOrderId(Long withdrawOrderId) {
        return Optional.ofNullable(toDomain(mapper.selectByWithdrawOrderId(withdrawOrderId)));
    }

    @Override
    public List<WalletWithdrawTx> findBroadcastedBefore(OffsetDateTime threshold, int limit) {
        return mapper.selectBroadcastedBefore(WalletMybatisSupport.toLocalDateTime(threshold), limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public int markBroadcastAtomic(Long id, String txHash, BigDecimal gasPrice, OffsetDateTime broadcastAt) {
        return mapper.markBroadcastAtomic(id, txHash, gasPrice,
                WalletMybatisSupport.toLocalDateTime(broadcastAt));
    }

    @Override
    public int markConfirmedAtomic(Long id, long blockNumber, int confirmations,
                                    BigDecimal gasUsed, BigDecimal gasFeeUsd, OffsetDateTime confirmedAt) {
        return mapper.markConfirmedAtomic(id, blockNumber, confirmations, gasUsed, gasFeeUsd,
                WalletMybatisSupport.toLocalDateTime(confirmedAt));
    }

    @Override
    public int markFailedAtomic(Long id, String failureCode, String failureReason, OffsetDateTime failedAt) {
        return mapper.markFailedAtomic(id, failureCode, failureReason,
                WalletMybatisSupport.toLocalDateTime(failedAt));
    }

    @Override
    public int updateConfirmations(Long id, int confirmations) {
        return mapper.updateConfirmations(id, confirmations);
    }

    private WalletWithdrawTx toDomain(WalletWithdrawTxRecord r) {
        if (r == null) return null;
        return new WalletWithdrawTx(
                r.id(),
                r.withdrawOrderId(),
                r.userId(),
                r.network(),
                r.fromAddress(),
                r.targetAddress(),
                r.amount(),
                r.nonce() == null ? 0L : r.nonce(),
                r.gasPrice(),
                r.gasUsed(),
                r.gasFeeUsd(),
                r.txHash(),
                r.blockNumber(),
                r.confirmations() == null ? 0 : r.confirmations(),
                WalletWithdrawTxStatus.fromCode(r.status()),
                r.failureCode(),
                r.failureReason(),
                WalletMybatisSupport.toOffsetDateTime(r.broadcastAt()),
                WalletMybatisSupport.toOffsetDateTime(r.confirmedAt()),
                WalletMybatisSupport.toOffsetDateTime(r.failedAt()),
                WalletMybatisSupport.toOffsetDateTime(r.createdAt()),
                WalletMybatisSupport.toOffsetDateTime(r.updatedAt())
        );
    }
}
