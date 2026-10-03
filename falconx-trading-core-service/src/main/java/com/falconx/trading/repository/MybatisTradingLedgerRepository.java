package com.falconx.trading.repository;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.TradingLedgerEntry;
import com.falconx.trading.repository.mapper.TradingLedgerMapper;
import com.falconx.trading.repository.mapper.record.TradingLedgerRecord;
import com.falconx.trading.repository.mapper.record.TradingSwapSettlementRecord;
import com.falconx.trading.service.model.TradingSwapSettlementView;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 交易账本 Repository 的 MyBatis 实现。
 *
 * <p>该实现保持账本“只追加、不覆盖”的语义，
 * 并通过 XML SQL 落库三维资金快照。
 */
@Repository
public class MybatisTradingLedgerRepository implements TradingLedgerRepository {

    private final TradingLedgerMapper tradingLedgerMapper;
    private final IdGenerator idGenerator;

    public MybatisTradingLedgerRepository(TradingLedgerMapper tradingLedgerMapper, IdGenerator idGenerator) {
        this.tradingLedgerMapper = tradingLedgerMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public TradingLedgerEntry save(TradingLedgerEntry entry) {
        long id = entry.ledgerId() == null ? idGenerator.nextId() : entry.ledgerId();
        TradingLedgerEntry persisted = entry.ledgerId() == null
                ? new TradingLedgerEntry(
                id,
                entry.accountId(),
                entry.userId(),
                entry.bizType(),
                entry.amount(),
                entry.originalAmount(),
                entry.originalCurrency(),
                entry.fxRateAtSettlement(),
                entry.idempotencyKey(),
                entry.referenceNo(),
                entry.balanceBefore(),
                entry.balanceAfter(),
                entry.frozenBefore(),
                entry.frozenAfter(),
                entry.marginUsedBefore(),
                entry.marginUsedAfter(),
                entry.createdAt()
        )
                : entry;
        tradingLedgerMapper.insertTradingLedger(toRecord(persisted));
        return persisted;
    }

    @Override
    public List<TradingLedgerEntry> batchInsert(List<TradingLedgerEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        java.util.List<TradingLedgerRecord> records = new java.util.ArrayList<>(entries.size());
        java.util.List<TradingLedgerEntry> persisted = new java.util.ArrayList<>(entries.size());
        for (TradingLedgerEntry entry : entries) {
            long id = entry.ledgerId() == null ? idGenerator.nextId() : entry.ledgerId();
            TradingLedgerEntry withId = entry.ledgerId() == null
                    ? new TradingLedgerEntry(
                            id,
                            entry.accountId(),
                            entry.userId(),
                            entry.bizType(),
                            entry.amount(),
                            entry.originalAmount(),
                            entry.originalCurrency(),
                            entry.fxRateAtSettlement(),
                            entry.idempotencyKey(),
                            entry.referenceNo(),
                            entry.balanceBefore(),
                            entry.balanceAfter(),
                            entry.frozenBefore(),
                            entry.frozenAfter(),
                            entry.marginUsedBefore(),
                            entry.marginUsedAfter(),
                            entry.createdAt())
                    : entry;
            records.add(toRecord(withId));
            persisted.add(withId);
        }
        tradingLedgerMapper.batchInsert(records);
        return persisted;
    }

    @Override
    public List<TradingLedgerEntry> findByUserId(Long userId) {
        return tradingLedgerMapper.selectByUserId(userId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<TradingLedgerEntry> findByUserIdPaginated(Long userId, int offset, int limit) {
        return tradingLedgerMapper.selectByUserIdPaginated(userId, offset, limit).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countByUserId(Long userId) {
        return tradingLedgerMapper.countByUserId(userId);
    }

    @Override
    public List<TradingLedgerEntry> findByUserIdFiltered(Long userId,
                                                         com.falconx.trading.entity.TradingLedgerBizType bizType,
                                                         OffsetDateTime from,
                                                         OffsetDateTime to,
                                                         int offset,
                                                         int limit) {
        Integer code = bizType == null ? null : TradingMybatisSupport.toLedgerBizTypeCode(bizType);
        return tradingLedgerMapper.selectByUserIdFiltered(
                userId,
                code,
                from == null ? null : from.toLocalDateTime(),
                to == null ? null : to.toLocalDateTime(),
                offset,
                limit
        ).stream().map(this::toDomain).toList();
    }

    @Override
    public long countByUserIdFiltered(Long userId,
                                      com.falconx.trading.entity.TradingLedgerBizType bizType,
                                      OffsetDateTime from,
                                      OffsetDateTime to) {
        Integer code = bizType == null ? null : TradingMybatisSupport.toLedgerBizTypeCode(bizType);
        return tradingLedgerMapper.countByUserIdFiltered(
                userId,
                code,
                from == null ? null : from.toLocalDateTime(),
                to == null ? null : to.toLocalDateTime()
        );
    }

    @Override
    public com.falconx.trading.dto.TradingSwapSummaryResponse aggregateSwap(Long userId,
                                                                            Long positionId,
                                                                            OffsetDateTime from,
                                                                            OffsetDateTime to) {
        String referenceLike = positionId == null ? null : "swap:" + positionId + ":%";
        List<com.falconx.trading.repository.mapper.record.TradingSwapAggregateRow> rows =
                tradingLedgerMapper.selectSwapAggregate(
                        userId,
                        referenceLike,
                        from == null ? null : from.toLocalDateTime(),
                        to == null ? null : to.toLocalDateTime()
                );
        if (rows.isEmpty()) {
            return com.falconx.trading.dto.TradingSwapSummaryResponse.empty();
        }
        java.math.BigDecimal totalCharge = java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalIncome = java.math.BigDecimal.ZERO;
        int chargeCount = 0;
        int incomeCount = 0;
        LocalDateTime firstAt = null;
        LocalDateTime lastAt = null;
        for (var row : rows) {
            if (row.bizType() == 6) {
                totalCharge = row.totalAmount() == null ? java.math.BigDecimal.ZERO : row.totalAmount();
                chargeCount = row.entryCount();
            } else if (row.bizType() == 7) {
                totalIncome = row.totalAmount() == null ? java.math.BigDecimal.ZERO : row.totalAmount();
                incomeCount = row.entryCount();
            }
            if (row.firstAt() != null && (firstAt == null || row.firstAt().isBefore(firstAt))) {
                firstAt = row.firstAt();
            }
            if (row.lastAt() != null && (lastAt == null || row.lastAt().isAfter(lastAt))) {
                lastAt = row.lastAt();
            }
        }
        java.math.BigDecimal net = totalIncome.subtract(totalCharge);
        return new com.falconx.trading.dto.TradingSwapSummaryResponse(
                totalCharge,
                totalIncome,
                net,
                chargeCount,
                incomeCount,
                firstAt == null ? null : firstAt.atOffset(java.time.ZoneOffset.UTC),
                lastAt == null ? null : lastAt.atOffset(java.time.ZoneOffset.UTC)
        );
    }

    @Override
    public List<TradingSwapSettlementView> findSwapSettlementsByUserId(Long userId, int offset, int limit) {
        return tradingLedgerMapper.selectSwapSettlementsByUserId(userId, offset, limit).stream()
                .map(this::toSwapSettlementView)
                .toList();
    }

    @Override
    public long countSwapSettlementsByUserId(Long userId) {
        return tradingLedgerMapper.countSwapSettlementsByUserId(userId);
    }

    @Override
    public boolean existsByUserIdAndIdempotencyKey(Long userId, String idempotencyKey) {
        return tradingLedgerMapper.countByUserIdAndIdempotencyKey(userId, idempotencyKey) > 0;
    }

    @Override
    public Optional<OffsetDateTime> findLatestSwapSettlementAt(Long userId, Long positionId) {
        LocalDateTime latestCreatedAt = tradingLedgerMapper.selectLatestSwapSettlementAt(userId, positionId);
        return latestCreatedAt == null
                ? Optional.empty()
                : Optional.of(TradingMybatisSupport.toOffsetDateTime(latestCreatedAt));
    }

    private TradingLedgerRecord toRecord(TradingLedgerEntry entry) {
        return new TradingLedgerRecord(
                entry.ledgerId(),
                entry.userId(),
                entry.accountId(),
                TradingMybatisSupport.toLedgerBizTypeCode(entry.bizType()),
                entry.idempotencyKey(),
                entry.referenceNo(),
                entry.amount(),
                entry.originalAmount(),
                entry.originalCurrency(),
                entry.fxRateAtSettlement(),
                entry.balanceBefore(),
                entry.balanceAfter(),
                entry.frozenBefore(),
                entry.frozenAfter(),
                entry.marginUsedBefore(),
                entry.marginUsedAfter(),
                TradingMybatisSupport.toLocalDateTime(entry.createdAt())
        );
    }

    private TradingLedgerEntry toDomain(TradingLedgerRecord record) {
        return new TradingLedgerEntry(
                record.id(),
                record.accountId(),
                record.userId(),
                TradingMybatisSupport.toLedgerBizType(record.bizTypeCode()),
                record.amount(),
                record.originalAmount(),
                record.originalCurrency(),
                record.fxRateAtSettlement(),
                record.idempotencyKey(),
                record.referenceNo(),
                record.balanceBefore(),
                record.balanceAfter(),
                record.frozenBefore(),
                record.frozenAfter(),
                record.marginUsedBefore(),
                record.marginUsedAfter(),
                TradingMybatisSupport.toOffsetDateTime(record.createdAt())
        );
    }

    private TradingSwapSettlementView toSwapSettlementView(TradingSwapSettlementRecord record) {
        return new TradingSwapSettlementView(
                record.ledgerId(),
                record.positionId(),
                record.symbol(),
                record.sideCode() == null ? null : TradingMybatisSupport.toSide(record.sideCode()),
                TradingMybatisSupport.toLedgerBizType(record.bizTypeCode()),
                record.amount(),
                record.balanceAfter(),
                parseRolloverAt(record.rolloverAtText()),
                TradingMybatisSupport.toOffsetDateTime(record.settledAt()),
                record.referenceNo()
        );
    }

    private OffsetDateTime parseRolloverAt(String rolloverAtText) {
        if (rolloverAtText == null || rolloverAtText.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(rolloverAtText);
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("Invalid swap rolloverAt text: " + rolloverAtText, exception);
        }
    }
}
