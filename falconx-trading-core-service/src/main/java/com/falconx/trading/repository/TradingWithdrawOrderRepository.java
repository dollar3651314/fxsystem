package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingWithdrawOrder;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * STAGE-7-WITHDRAW：出金主表仓储。
 */
public interface TradingWithdrawOrderRepository {

    void insert(TradingWithdrawOrder order);

    Optional<TradingWithdrawOrder> findById(Long id);

    Optional<TradingWithdrawOrder> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    List<TradingWithdrawOrder> findByUserId(Long userId, Integer statusCode, int offset, int limit);

    long countByUserId(Long userId, Integer statusCode);

    /**
     * 单日累计金额（USDT，1:1 USD），用于 30K 上限校验。
     *
     * @param userId 用户 ID
     * @param dayStartUtc 当日 UTC 起始时间（含）
     * @param dayEndUtc 次日 UTC 起始时间（不含）
     * @return 累计金额；无记录返回 0
     */
    BigDecimal sumActiveAmountForUserOnDay(Long userId, OffsetDateTime dayStartUtc, OffsetDateTime dayEndUtc);

    /**
     * STAGE-7-WITHDRAW Phase 2：用户冷静期取消的 CAS 更新。
     *
     * <p>SQL：{@code UPDATE ... SET status=7 WHERE id=? AND user_id=? AND status=0}（COOLING）。
     *
     * @return 影响行数；0 表示状态非 COOLING 或非 owner（应抛 30048）
     */
    int markCanceledByUserAtomic(Long withdrawId, Long userId);

    /**
     * STAGE-7-WITHDRAW Phase 2：扫描已过冷静期的 COOLING 记录（供 WithdrawCoolingScheduler 推进 → PENDING）。
     */
    List<TradingWithdrawOrder> findExpiredCoolingOrders(OffsetDateTime now, int limit);

    /**
     * STAGE-7-WITHDRAW Phase 2：CAS 把 COOLING(0) 推进到 PENDING(1)。
     *
     * @return 1 = 成功；0 = 并发竞态（已被切走）
     */
    int markPendingFromCoolingAtomic(Long withdrawId);

    /**
     * STAGE-7-WITHDRAW Phase 2：admin 审核列表。
     *
     * @param status 可选过滤
     * @param userId 可选过滤
     * @param network 可选过滤（ERC20/TRC20）
     * @param minAmount 可选过滤（≥）
     */
    List<TradingWithdrawOrder> findAdminPaginated(Integer status, Long userId, String network,
                                                    java.math.BigDecimal minAmount, int offset, int limit);

    long countAdminFiltered(Integer status, Long userId, String network, java.math.BigDecimal minAmount);

    /** admin approve CAS（nextStatus = APPROVED 或 APPROVED_DELAYED）。 */
    int markReviewedFromPendingAtomic(Long withdrawId, int nextStatusCode, long reviewerId,
                                        OffsetDateTime reviewAt, String reviewNote, OffsetDateTime delayedUntil);

    /** admin reject CAS。 */
    int markRejectedFromPendingAtomic(Long withdrawId, long reviewerId, OffsetDateTime reviewAt, String rejectReason);

    /** admin emergency-cancel CAS。 */
    int markCanceledByAdminAtomic(Long withdrawId, long reviewerId, OffsetDateTime reviewAt, String rejectReason);

    /** 扫描已过大额延迟期的 APPROVED_DELAYED 记录。 */
    List<TradingWithdrawOrder> findExpiredDelayedOrders(OffsetDateTime now, int limit);

    /** CAS：APPROVED_DELAYED(3) → APPROVED(2)。 */
    int markApprovedFromDelayedAtomic(Long withdrawId);

    /** STAGE-7-WITHDRAW Phase 3：CAS APPROVED(2) → PROCESSING(4) + 写 tx_hash + processing_started_at。 */
    int markProcessingFromApprovedAtomic(Long withdrawId, String txHash, OffsetDateTime processingStartedAt);

    /** STAGE-7-WITHDRAW Phase 3：CAS PROCESSING(4) → COMPLETED(5) + 写 confirmations。 */
    int markCompletedFromProcessingAtomic(Long withdrawId, int confirmations, OffsetDateTime updatedAt);

    /**
     * STAGE-7-WITHDRAW Phase 3：CAS 任意非终态 → FAILED(6) + 写 failure_code / failure_reason。
     * 非终态 = {APPROVED(2), APPROVED_DELAYED(3), PROCESSING(4)}。
     */
    int markFailedAtomic(Long withdrawId, String failureCode, String failureReason, OffsetDateTime updatedAt);
}
