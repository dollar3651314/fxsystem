package com.falconx.market.repository;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.market.entity.MarketOutboxMessage;
import com.falconx.market.entity.MarketOutboxStatus;
import com.falconx.market.repository.mapper.MarketOutboxMapper;
import com.falconx.market.repository.mapper.record.MarketOutboxRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * market-service 发件箱仓储的 MyBatis 实现。
 *
 * <p>该实现把低频市场事件的五态状态机正式落到 `t_outbox`，
 * 确保 `market.kline.update` 这类事件遵循本地事务加 Outbox 语义。
 */
@Repository
@Profile("!stub")
public class MybatisMarketOutboxRepository implements MarketOutboxRepository {

    private final MarketOutboxMapper marketOutboxMapper;
    private final IdGenerator idGenerator;

    public MybatisMarketOutboxRepository(MarketOutboxMapper marketOutboxMapper, IdGenerator idGenerator) {
        this.marketOutboxMapper = marketOutboxMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public MarketOutboxMessage save(MarketOutboxMessage message) {
        String outboxId = message.outboxId() == null ? String.valueOf(idGenerator.nextId()) : message.outboxId();
        String eventId = message.eventId() == null ? "evt-" + idGenerator.nextId() : message.eventId();
        OffsetDateTime createdAt = message.createdAt() == null ? OffsetDateTime.now() : message.createdAt();
        MarketOutboxMessage persisted = new MarketOutboxMessage(
                outboxId,
                eventId,
                message.eventType(),
                message.partitionKey(),
                message.payload(),
                message.status(),
                createdAt,
                message.sentAt(),
                message.retryCount(),
                message.nextRetryAt(),
                message.lastError()
        );
        marketOutboxMapper.upsertMarketOutbox(toRecord(persisted));
        return persisted;
    }

    @Override
    @Transactional
    public List<MarketOutboxMessage> claimDispatchableBatch(OffsetDateTime now, int limit) {
        List<MarketOutboxRecord> dispatchableRecords = marketOutboxMapper.selectDispatchableBatch(
                MarketMetadataMybatisSupport.toLocalDateTime(now),
                limit
        );
        OffsetDateTime claimedAt = OffsetDateTime.now();
        for (MarketOutboxRecord record : dispatchableRecords) {
            marketOutboxMapper.updateDispatchingById(record.id(), MarketMetadataMybatisSupport.toLocalDateTime(claimedAt));
        }
        return dispatchableRecords.stream()
                .map(this::toDomain)
                .map(message -> new MarketOutboxMessage(
                        message.outboxId(),
                        message.eventId(),
                        message.eventType(),
                        message.partitionKey(),
                        message.payload(),
                        MarketOutboxStatus.DISPATCHING,
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
        marketOutboxMapper.updateSentById(Long.parseLong(outboxId), MarketMetadataMybatisSupport.toLocalDateTime(sentAt));
    }

    @Override
    public void markFailed(String outboxId, OffsetDateTime nextRetryAt, String lastError, int maxRetryCount) {
        MarketOutboxMessage existing = findByOutboxId(outboxId)
                .orElseThrow(() -> new IllegalStateException("Market outbox message not found: " + outboxId));
        int nextRetryCount = existing.retryCount() + 1;
        MarketOutboxStatus nextStatus = nextRetryCount >= maxRetryCount
                ? MarketOutboxStatus.DEAD
                : MarketOutboxStatus.FAILED;
        marketOutboxMapper.updateFailedById(
                Long.parseLong(outboxId),
                MarketMetadataMybatisSupport.toOutboxStatusCode(nextStatus),
                nextRetryCount,
                nextStatus == MarketOutboxStatus.DEAD ? null : MarketMetadataMybatisSupport.toLocalDateTime(nextRetryAt),
                lastError,
                MarketMetadataMybatisSupport.toLocalDateTime(OffsetDateTime.now())
        );
    }

    @Override
    public Optional<MarketOutboxMessage> findByOutboxId(String outboxId) {
        return Optional.ofNullable(toDomain(marketOutboxMapper.selectById(Long.parseLong(outboxId))));
    }

    @Override
    public int deleteSentBefore(OffsetDateTime cutoff, int limit) {
        return marketOutboxMapper.deleteSentBefore(MarketMetadataMybatisSupport.toLocalDateTime(cutoff), limit);
    }

    private MarketOutboxRecord toRecord(MarketOutboxMessage message) {
        return new MarketOutboxRecord(
                Long.parseLong(message.outboxId()),
                message.eventId(),
                message.eventType(),
                topicOf(message.eventType()),
                message.partitionKey(),
                MarketMetadataMybatisSupport.toJson(message.payload()),
                MarketMetadataMybatisSupport.toOutboxStatusCode(message.status()),
                message.retryCount(),
                MarketMetadataMybatisSupport.toLocalDateTime(message.nextRetryAt()),
                message.lastError(),
                MarketMetadataMybatisSupport.toLocalDateTime(message.createdAt()),
                MarketMetadataMybatisSupport.toLocalDateTime(message.sentAt()),
                MarketMetadataMybatisSupport.toLocalDateTime(OffsetDateTime.now())
        );
    }

    private MarketOutboxMessage toDomain(MarketOutboxRecord record) {
        if (record == null) {
            return null;
        }
        return new MarketOutboxMessage(
                String.valueOf(record.id()),
                record.eventId(),
                record.eventType(),
                record.partitionKey(),
                MarketMetadataMybatisSupport.readJsonObject(record.payloadJson()),
                MarketMetadataMybatisSupport.toOutboxStatus(record.statusCode()),
                MarketMetadataMybatisSupport.toOffsetDateTime(record.createdAt()),
                MarketMetadataMybatisSupport.toOffsetDateTime(record.sentAt()),
                record.retryCount(),
                MarketMetadataMybatisSupport.toOffsetDateTime(record.nextRetryAt()),
                record.lastError()
        );
    }

    private String topicOf(String eventType) {
        if ("market.kline.update".equals(eventType)) {
            return "falconx.market.kline.update";
        }
        throw new IllegalStateException("Unsupported market outbox event type: " + eventType);
    }
}
