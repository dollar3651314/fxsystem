package com.falconx.wallet.repository.mapper;

import com.falconx.wallet.repository.mapper.record.WalletWithdrawTxRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * STAGE-7-WITHDRAW Phase 3：链上出金交易 Mapper。
 */
@Mapper
public interface WalletWithdrawTxMapper {

    int insert(WalletWithdrawTxRecord record);

    WalletWithdrawTxRecord selectById(@Param("id") Long id);

    WalletWithdrawTxRecord selectByWithdrawOrderId(@Param("withdrawOrderId") Long withdrawOrderId);

    /**
     * 扫描 BROADCAST 状态的 tx（监听器轮询 / 超时调度器使用）。
     */
    List<WalletWithdrawTxRecord> selectBroadcastedBefore(@Param("threshold") LocalDateTime threshold,
                                                          @Param("limit") int limit);

    /**
     * SIGNING → BROADCAST（CAS）：广播成功后回填 tx_hash + gas_price + nonce + broadcast_at。
     */
    int markBroadcastAtomic(@Param("id") Long id,
                             @Param("txHash") String txHash,
                             @Param("gasPrice") BigDecimal gasPrice,
                             @Param("broadcastAt") LocalDateTime broadcastAt);

    /**
     * 任意状态 → CONFIRMED（要求当前 BROADCAST）：写 block_number + confirmations + gas_used + gas_fee_usd + confirmed_at。
     */
    int markConfirmedAtomic(@Param("id") Long id,
                             @Param("blockNumber") long blockNumber,
                             @Param("confirmations") int confirmations,
                             @Param("gasUsed") BigDecimal gasUsed,
                             @Param("gasFeeUsd") BigDecimal gasFeeUsd,
                             @Param("confirmedAt") LocalDateTime confirmedAt);

    /**
     * 任意非终态 → FAILED：写 failure_code + failure_reason + failed_at。
     */
    int markFailedAtomic(@Param("id") Long id,
                          @Param("failureCode") String failureCode,
                          @Param("failureReason") String failureReason,
                          @Param("failedAt") LocalDateTime failedAt);

    /**
     * 更新链上确认数（在 receipt 出来但未达 min_confirmations 时使用）。
     */
    int updateConfirmations(@Param("id") Long id,
                             @Param("confirmations") int confirmations);
}
