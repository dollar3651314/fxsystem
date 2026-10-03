package com.falconx.trading.repository;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.entity.TradingOutboxMessage;
import com.falconx.trading.entity.TradingOutboxStatus;
import com.falconx.trading.repository.mapper.TradingOutboxMapper;
import com.falconx.trading.repository.mapper.record.TradingOutboxRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 交易 Outbox Repository 的 MyBatis 实现。
 *
 * <p>该实现把 Outbox 的五态状态机正式落到 `t_outbox`，
 * 同时保持 Repository 层只负责参数组织和状态转换。
 */
@Repository
public class MybatisTradingOutboxRepository implements TradingOutboxRepository {

    private final TradingOutboxMapper tradingOutboxMapper;
    private final IdGenerator idGenerator;

    public MybatisTradingOutboxRepository(TradingOutboxMapper tradingOutboxMapper, IdGenerator idGenerator) {
        this.tradingOutboxMapper = tradingOutboxMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public TradingOutboxMessage save(TradingOutboxMessage message) {
        String outboxId = message.outboxId() == null ? String.valueOf(idGenerator.nextId()) : message.outboxId();
        OffsetDateTime createdAt = message.createdAt() == null ? OffsetDateTime.now() : message.createdAt();
        TradingOutboxMessage persisted = message.outboxId() == null
                ? new TradingOutboxMessage(
                outboxId,
                message.eventId(),
                message.eventType(),
                message.partitionKey(),
                message.payload(),
                message.status(),
                createdAt,
                message.sentAt(),
                message.retryCount(),
                message.nextRetryAt(),
                message.lastError()
        )
                : message;
        tradingOutboxMapper.upsertTradingOutbox(toRecord(persisted));
        return persisted;
    }

    @Override
    public List<TradingOutboxMessage> findPending() {
        return tradingOutboxMapper.selectPending().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public List<TradingOutboxMessage> claimDispatchableBatch(OffsetDateTime now, int limit) {
        List<TradingOutboxRecord> dispatchableRecords = tradingOutboxMapper.selectDispatchableBatch(
                TradingMybatisSupport.toLocalDateTime(now),
                limit
        );
        OffsetDateTime claimedAt = OffsetDateTime.now();
        for (TradingOutboxRecord record : dispatchableRecords) {
            tradingOutboxMapper.updateDispatchingById(
                    record.id(),
                    TradingMybatisSupport.toLocalDateTime(claimedAt)
            );
        }
        return dispatchableRecords.stream()
                .map(this::toDomain)
                .map(message -> new TradingOutboxMessage(
                        message.outboxId(),
                        message.eventId(),
                        message.eventType(),
                        message.partitionKey(),
                        message.payload(),
                        TradingOutboxStatus.DISPATCHING,
                        message.createdAt(),
                        message.sentAt(),
                        message.retryCount(),
                        message.nextRetryAt(),
                        message.lastError()
                ))
                .toList();
    }

    @Override
    public void markSent(String outboxId, OffsetDateTime sentAt) {
        tradingOutboxMapper.updateSentById(Long.parseLong(outboxId), TradingMybatisSupport.toLocalDateTime(sentAt));
    }

    @Override
    public void markFailed(String outboxId, OffsetDateTime nextRetryAt, String lastError, int maxRetryCount) {
        TradingOutboxMessage existing = findByOutboxId(outboxId)
                .orElseThrow(() -> new IllegalStateException("Outbox message not found: " + outboxId));
        int nextRetryCount = existing.retryCount() + 1;
        TradingOutboxStatus nextStatus = nextRetryCount >= maxRetryCount
                ? TradingOutboxStatus.DEAD
                : TradingOutboxStatus.FAILED;
        tradingOutboxMapper.updateFailedById(
                Long.parseLong(outboxId),
                TradingMybatisSupport.toOutboxStatusCode(nextStatus),
                nextRetryCount,
                nextStatus == TradingOutboxStatus.DEAD ? null : TradingMybatisSupport.toLocalDateTime(nextRetryAt),
                lastError,
                TradingMybatisSupport.toLocalDateTime(OffsetDateTime.now())
        );
    }

    @Override
    public Optional<TradingOutboxMessage> findByOutboxId(String outboxId) {
        return Optional.ofNullable(toDomain(tradingOutboxMapper.selectById(Long.parseLong(outboxId))));
    }

    @Override
    public int deleteSentBefore(OffsetDateTime cutoff, int limit) {
        return tradingOutboxMapper.deleteSentBefore(TradingMybatisSupport.toLocalDateTime(cutoff), limit);
    }

    private TradingOutboxRecord toRecord(TradingOutboxMessage message) {
        return new TradingOutboxRecord(
                Long.parseLong(message.outboxId()),
                message.eventId(),
                message.eventType(),
                topicOf(message.eventType()),
                message.partitionKey(),
                TradingMybatisSupport.toJson(message.payload()),
                TradingMybatisSupport.toOutboxStatusCode(message.status()),
                message.retryCount(),
                TradingMybatisSupport.toLocalDateTime(message.nextRetryAt()),
                message.lastError(),
                TradingMybatisSupport.toLocalDateTime(message.createdAt()),
                TradingMybatisSupport.toLocalDateTime(message.sentAt()),
                TradingMybatisSupport.toLocalDateTime(OffsetDateTime.now())
        );
    }

    private TradingOutboxMessage toDomain(TradingOutboxRecord record) {
        if (record == null) {
            return null;
        }
        return new TradingOutboxMessage(
                String.valueOf(record.id()),
                record.eventId(),
                record.eventType(),
                record.partitionKey(),
                TradingMybatisSupport.readJsonObject(record.payloadJson()),
                TradingMybatisSupport.toOutboxStatus(record.statusCode()),
                TradingMybatisSupport.toOffsetDateTime(record.createdAt()),
                TradingMybatisSupport.toOffsetDateTime(record.sentAt()),
                record.retryCount(),
                TradingMybatisSupport.toOffsetDateTime(record.nextRetryAt()),
                record.lastError()
        );
    }

    private String topicOf(String eventType) {
        if ("trading.deposit.credited".equals(eventType)) {
            return "falconx.trading.deposit.credited";
        }
        return "falconx." + eventType;
    }
}
