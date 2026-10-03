package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingWithdrawOrderRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * STAGE-7-WITHDRAW：出金主表 Mapper。
 */
@Mapper
public interface TradingWithdrawOrderMapper {

    int insert(TradingWithdrawOrderRecord record);

    TradingWithdrawOrderRecord selectById(@Param("id") Long id);

    TradingWithdrawOrderRecord selectByUserIdAndIdempotencyKey(@Param("userId") Long userId,
                                                                @Param("idempotencyKey") String idempotencyKey);

    /**
     * 用户列表（status 可选过滤）。排序按 created_at DESC, id DESC。
     */
    List<TradingWithdrawOrderRecord> selectByUserId(@Param("userId") Long userId,
                                                     @Param("status") Integer status,
                                                     @Param("offset") int offset,
                                                     @Param("limit") int limit);

    long countByUserId(@Param("userId") Long userId,
                       @Param("status") Integer status);

    /**
     * 单日累计美元（COOLING/PENDING/APPROVED/APPROVED_DELAYED + COMPLETED 已计入），
     * 按 created_at 当 UTC 日聚合 SUM(amount)。
     *
     * <p>金额按 USDT 1:1 USD 处理（一期固定 USDT）。
     */
    BigDecimal sumActiveAmountForUserOnDay(@Param("userId") Long userId,
                                             @Param("dayStartUtc") LocalDateTime dayStartUtc,
                                             @Param("dayEndUtc") LocalDateTime dayEndUtc);

    /**
     * STAGE-7-WITHDRAW Phase 2：用户冷静期取消（CAS）。
     *
     * @return 影响行数；0 表示状态非 COOLING 或非 owner
     */
    int markCanceledByUserAtomic(@Param("id") Long id,
                                  @Param("userId") Long userId,
                                  @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * STAGE-7-WITHDRAW Phase 2：扫描已过冷静期的 COOLING 记录（最多 {@code limit} 条）。
     */
    List<TradingWithdrawOrderRecord> selectExpiredCoolingOrders(@Param("now") LocalDateTime now,
                                                                  @Param("limit") int limit);

    /**
     * STAGE-7-WITHDRAW Phase 2：CAS 把 COOLING(0) 推进到 PENDING(1)。
     *
     * @return 影响行数；0 表示已被其他线程切走
     */
    int markPendingFromCoolingAtomic(@Param("id") Long id,
                                      @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * STAGE-7-WITHDRAW Phase 2：admin 审核列表。排序：PENDING 优先 + APPROVED_DELAYED 次优先 + 其余按 created_at DESC。
     */
    List<TradingWithdrawOrderRecord> selectAdminPaginated(@Param("status") Integer status,
                                                            @Param("userId") Long userId,
                                                            @Param("network") String network,
                                                            @Param("minAmount") java.math.BigDecimal minAmount,
                                                            @Param("offset") int offset,
                                                            @Param("limit") int limit);

    long countAdminFiltered(@Param("status") Integer status,
                            @Param("userId") Long userId,
                            @Param("network") String network,
                            @Param("minAmount") java.math.BigDecimal minAmount);

    /**
     * STAGE-7-WITHDRAW Phase 2：admin approve（PENDING→APPROVED 或 APPROVED_DELAYED）的 CAS 更新。
     *
     * @param nextStatus 2=APPROVED / 3=APPROVED_DELAYED
     * @param delayedUntil APPROVED_DELAYED 时填，否则 null
     * @return 1 = 成功；0 = 状态非 PENDING
     */
    int markReviewedFromPendingAtomic(@Param("id") Long id,
                                       @Param("nextStatus") int nextStatus,
                                       @Param("reviewerId") long reviewerId,
                                       @Param("reviewAt") LocalDateTime reviewAt,
                                       @Param("reviewNote") String reviewNote,
                                       @Param("delayedUntil") LocalDateTime delayedUntil);

    /**
     * STAGE-7-WITHDRAW Phase 2：admin reject（PENDING→REJECTED）的 CAS 更新。
     */
    int markRejectedFromPendingAtomic(@Param("id") Long id,
                                       @Param("reviewerId") long reviewerId,
                                       @Param("reviewAt") LocalDateTime reviewAt,
                                       @Param("rejectReason") String rejectReason);

    /**
     * STAGE-7-WITHDRAW Phase 2：admin emergency-cancel（APPROVED_DELAYED→CANCELED）的 CAS 更新。
     */
    int markCanceledByAdminAtomic(@Param("id") Long id,
                                   @Param("reviewerId") long reviewerId,
                                   @Param("reviewAt") LocalDateTime reviewAt,
                                   @Param("rejectReason") String rejectReason);

    /**
     * STAGE-7-WITHDRAW Phase 2：扫描已过大额延迟期的 APPROVED_DELAYED 记录。
     */
    List<TradingWithdrawOrderRecord> selectExpiredDelayedOrders(@Param("now") LocalDateTime now,
                                                                  @Param("limit") int limit);

    /**
     * STAGE-7-WITHDRAW Phase 2：CAS 把 APPROVED_DELAYED(3) 推进到 APPROVED(2)。
     */
    int markApprovedFromDelayedAtomic(@Param("id") Long id,
                                       @Param("updatedAt") LocalDateTime updatedAt);

    /** STAGE-7-WITHDRAW Phase 3：CAS APPROVED(2) → PROCESSING(4) + 写 tx_hash + processing_started_at。 */
    int markProcessingFromApprovedAtomic(@Param("id") Long id,
                                          @Param("txHash") String txHash,
                                          @Param("processingStartedAt") LocalDateTime processingStartedAt);

    /** STAGE-7-WITHDRAW Phase 3：CAS PROCESSING(4) → COMPLETED(5)。 */
    int markCompletedFromProcessingAtomic(@Param("id") Long id,
                                           @Param("confirmations") int confirmations,
                                           @Param("updatedAt") LocalDateTime updatedAt);

    /** STAGE-7-WITHDRAW Phase 3：CAS 任意非终态 → FAILED(6)。 */
    int markFailedAtomic(@Param("id") Long id,
                          @Param("failureCode") String failureCode,
                          @Param("failureReason") String failureReason,
                          @Param("updatedAt") LocalDateTime updatedAt);
}
