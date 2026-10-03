package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingWithdrawNetwork;
import com.falconx.trading.entity.TradingWithdrawOrder;
import com.falconx.trading.entity.TradingWithdrawOrderStatus;
import com.falconx.trading.repository.mapper.TradingWithdrawOrderMapper;
import com.falconx.trading.repository.mapper.record.TradingWithdrawOrderRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * STAGE-7-WITHDRAW：出金主表仓储 MyBatis 实现。
 */
@Repository
public class MybatisTradingWithdrawOrderRepository implements TradingWithdrawOrderRepository {

    private final TradingWithdrawOrderMapper mapper;

    public MybatisTradingWithdrawOrderRepository(TradingWithdrawOrderMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(TradingWithdrawOrder order) {
        TradingWithdrawOrderRecord record = new TradingWithdrawOrderRecord(
                order.id(),
                order.userId(),
                order.amount(),
                order.currency(),
                order.network().name(),
                order.targetAddress(),
                order.whitelistId(),
                order.status().code(),
                toLocal(order.coolingUntil()),
                toLocal(order.delayedUntil()),
                toLocal(order.processingStartedAt()),
                order.reviewerId(),
                toLocal(order.reviewAt()),
                order.reviewNote(),
                order.rejectReason(),
                order.txHash(),
                order.confirmations(),
                order.failureCode(),
                order.failureReason(),
                order.idempotencyKey(),
                order.dailyAmountUsdSnapshot(),
                toLocal(order.createdAt()),
                toLocal(order.updatedAt())
        );
        mapper.insert(record);
    }

    @Override
    public Optional<TradingWithdrawOrder> findById(Long id) {
        return Optional.ofNullable(toDomain(mapper.selectById(id)));
    }

    @Override
    public Optional<TradingWithdrawOrder> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey) {
        if (userId == null || idempotencyKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(toDomain(mapper.selectByUserIdAndIdempotencyKey(userId, idempotencyKey)));
    }

    @Override
    public List<TradingWithdrawOrder> findByUserId(Long userId, Integer statusCode, int offset, int limit) {
        return mapper.selectByUserId(userId, statusCode, offset, limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long countByUserId(Long userId, Integer statusCode) {
        return mapper.countByUserId(userId, statusCode);
    }

    @Override
    public BigDecimal sumActiveAmountForUserOnDay(Long userId, OffsetDateTime dayStartUtc, OffsetDateTime dayEndUtc) {
        BigDecimal sum = mapper.sumActiveAmountForUserOnDay(userId,
                dayStartUtc.toLocalDateTime(), dayEndUtc.toLocalDateTime());
        return sum == null ? BigDecimal.ZERO : sum;
    }

    @Override
    public int markCanceledByUserAtomic(Long withdrawId, Long userId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return mapper.markCanceledByUserAtomic(withdrawId, userId, now);
    }

    @Override
    public List<TradingWithdrawOrder> findExpiredCoolingOrders(OffsetDateTime now, int limit) {
        return mapper.selectExpiredCoolingOrders(now.toLocalDateTime(), limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public int markPendingFromCoolingAtomic(Long withdrawId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return mapper.markPendingFromCoolingAtomic(withdrawId, now);
    }

    @Override
    public List<TradingWithdrawOrder> findAdminPaginated(Integer status, Long userId, String network,
                                                          java.math.BigDecimal minAmount, int offset, int limit) {
        return mapper.selectAdminPaginated(status, userId, network, minAmount, offset, limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public long countAdminFiltered(Integer status, Long userId, String network, java.math.BigDecimal minAmount) {
        return mapper.countAdminFiltered(status, userId, network, minAmount);
    }

    @Override
    public int markReviewedFromPendingAtomic(Long withdrawId, int nextStatusCode, long reviewerId,
                                              OffsetDateTime reviewAt, String reviewNote, OffsetDateTime delayedUntil) {
        return mapper.markReviewedFromPendingAtomic(withdrawId, nextStatusCode, reviewerId,
                toLocal(reviewAt), reviewNote, toLocal(delayedUntil));
    }

    @Override
    public int markRejectedFromPendingAtomic(Long withdrawId, long reviewerId, OffsetDateTime reviewAt, String rejectReason) {
        return mapper.markRejectedFromPendingAtomic(withdrawId, reviewerId, toLocal(reviewAt), rejectReason);
    }

    @Override
    public int markCanceledByAdminAtomic(Long withdrawId, long reviewerId, OffsetDateTime reviewAt, String rejectReason) {
        return mapper.markCanceledByAdminAtomic(withdrawId, reviewerId, toLocal(reviewAt), rejectReason);
    }

    @Override
    public List<TradingWithdrawOrder> findExpiredDelayedOrders(OffsetDateTime now, int limit) {
        return mapper.selectExpiredDelayedOrders(now.toLocalDateTime(), limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    public int markApprovedFromDelayedAtomic(Long withdrawId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return mapper.markApprovedFromDelayedAtomic(withdrawId, now);
    }

    private TradingWithdrawOrder toDomain(TradingWithdrawOrderRecord r) {
        if (r == null) return null;
        return new TradingWithdrawOrder(
                r.id(),
                r.userId(),
                r.amount(),
                r.currency(),
                TradingWithdrawNetwork.valueOf(r.network()),
                r.targetAddress(),
                r.whitelistId(),
                TradingWithdrawOrderStatus.fromCode(r.status()),
                TradingMybatisSupport.toOffsetDateTime(r.coolingUntil()),
                TradingMybatisSupport.toOffsetDateTime(r.delayedUntil()),
                TradingMybatisSupport.toOffsetDateTime(r.processingStartedAt()),
                r.reviewerId(),
                TradingMybatisSupport.toOffsetDateTime(r.reviewAt()),
                r.reviewNote(),
                r.rejectReason(),
                r.txHash(),
                r.confirmations() == null ? 0 : r.confirmations(),
                r.failureCode(),
                r.failureReason(),
                r.idempotencyKey(),
                r.dailyAmountUsdSnapshot(),
                TradingMybatisSupport.toOffsetDateTime(r.createdAt()),
                TradingMybatisSupport.toOffsetDateTime(r.updatedAt())
        );
    }

    private LocalDateTime toLocal(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    @Override
    public int markProcessingFromApprovedAtomic(Long withdrawId, String txHash, OffsetDateTime processingStartedAt) {
        return mapper.markProcessingFromApprovedAtomic(withdrawId, txHash, toLocal(processingStartedAt));
    }

    @Override
    public int markCompletedFromProcessingAtomic(Long withdrawId, int confirmations, OffsetDateTime updatedAt) {
        return mapper.markCompletedFromProcessingAtomic(withdrawId, confirmations, toLocal(updatedAt));
    }

    @Override
    public int markFailedAtomic(Long withdrawId, String failureCode, String failureReason, OffsetDateTime updatedAt) {
        return mapper.markFailedAtomic(withdrawId, failureCode, failureReason, toLocal(updatedAt));
    }
}
