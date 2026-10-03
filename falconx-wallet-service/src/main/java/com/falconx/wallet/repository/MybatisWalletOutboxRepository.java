package com.falconx.wallet.repository;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.wallet.entity.WalletOutboxMessage;
import com.falconx.wallet.entity.WalletOutboxStatus;
import com.falconx.wallet.repository.mapper.WalletOutboxMapper;
import com.falconx.wallet.repository.mapper.record.WalletOutboxRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * wallet-service 发件箱仓储的 MyBatis 实现。
 *
 * <p>该实现把钱包低频事件的五态状态机正式落到 `t_outbox`，
 * 确保业务事务和 Kafka 投递之间通过本地发件箱衔接。
 */
@Repository
public class MybatisWalletOutboxRepository implements WalletOutboxRepository {

    private final WalletOutboxMapper walletOutboxMapper;
    private final IdGenerator idGenerator;

    public MybatisWalletOutboxRepository(WalletOutboxMapper walletOutboxMapper, IdGenerator idGenerator) {
        this.walletOutboxMapper = walletOutboxMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public WalletOutboxMessage save(WalletOutboxMessage message) {
        String outboxId = message.outboxId() == null ? String.valueOf(idGenerator.nextId()) : message.outboxId();
        String eventId = message.eventId() == null ? "evt-" + idGenerator.nextId() : message.eventId();
        OffsetDateTime createdAt = message.createdAt() == null ? OffsetDateTime.now() : message.createdAt();
        WalletOutboxMessage persisted = new WalletOutboxMessage(
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
        walletOutboxMapper.upsertWalletOutbox(toRecord(persisted));
        return persisted;
    }

    @Override
    @Transactional
    public List<WalletOutboxMessage> claimDispatchableBatch(OffsetDateTime now, int limit) {
        List<WalletOutboxRecord> dispatchableRecords = walletOutboxMapper.selectDispatchableBatch(
                WalletMybatisSupport.toLocalDateTime(now),
                limit
        );
        OffsetDateTime claimedAt = OffsetDateTime.now();
        for (WalletOutboxRecord record : dispatchableRecords) {
            walletOutboxMapper.updateDispatchingById(record.id(), WalletMybatisSupport.toLocalDateTime(claimedAt));
        }
        return dispatchableRecords.stream()
                .map(this::toDomain)
                .map(message -> new WalletOutboxMessage(
                        message.outboxId(),
                        message.eventId(),
                        message.eventType(),
                        message.partitionKey(),
                        message.payload(),
                        WalletOutboxStatus.DISPATCHING,
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
        walletOutboxMapper.updateSentById(Long.parseLong(outboxId), WalletMybatisSupport.toLocalDateTime(sentAt));
    }

    @Override
    public void markFailed(String outboxId, OffsetDateTime nextRetryAt, String lastError, int maxRetryCount) {
        WalletOutboxMessage existing = findByOutboxId(outboxId)
                .orElseThrow(() -> new IllegalStateException("Wallet outbox message not found: " + outboxId));
        int nextRetryCount = existing.retryCount() + 1;
        WalletOutboxStatus nextStatus = nextRetryCount >= maxRetryCount
                ? WalletOutboxStatus.DEAD
                : WalletOutboxStatus.FAILED;
        walletOutboxMapper.updateFailedById(
                Long.parseLong(outboxId),
                WalletMybatisSupport.toOutboxStatusCode(nextStatus),
                nextRetryCount,
                nextStatus == WalletOutboxStatus.DEAD ? null : WalletMybatisSupport.toLocalDateTime(nextRetryAt),
                lastError,
                WalletMybatisSupport.toLocalDateTime(OffsetDateTime.now())
        );
    }

    @Override
    public Optional<WalletOutboxMessage> findByOutboxId(String outboxId) {
        return Optional.ofNullable(toDomain(walletOutboxMapper.selectById(Long.parseLong(outboxId))));
    }

    @Override
    public int deleteSentBefore(OffsetDateTime cutoff, int limit) {
        return walletOutboxMapper.deleteSentBefore(WalletMybatisSupport.toLocalDateTime(cutoff), limit);
    }

    private WalletOutboxRecord toRecord(WalletOutboxMessage message) {
        return new WalletOutboxRecord(
                Long.parseLong(message.outboxId()),
                message.eventId(),
                message.eventType(),
                topicOf(message.eventType()),
                message.partitionKey(),
                WalletMybatisSupport.toJson(message.payload()),
                WalletMybatisSupport.toOutboxStatusCode(message.status()),
                message.retryCount(),
                WalletMybatisSupport.toLocalDateTime(message.nextRetryAt()),
                message.lastError(),
                WalletMybatisSupport.toLocalDateTime(message.createdAt()),
                WalletMybatisSupport.toLocalDateTime(message.sentAt()),
                WalletMybatisSupport.toLocalDateTime(OffsetDateTime.now())
        );
    }

    private WalletOutboxMessage toDomain(WalletOutboxRecord record) {
        if (record == null) {
            return null;
        }
        return new WalletOutboxMessage(
                String.valueOf(record.id()),
                record.eventId(),
                record.eventType(),
                record.partitionKey(),
                WalletMybatisSupport.readJsonObject(record.payloadJson()),
                WalletMybatisSupport.toOutboxStatus(record.statusCode()),
                WalletMybatisSupport.toOffsetDateTime(record.createdAt()),
                WalletMybatisSupport.toOffsetDateTime(record.sentAt()),
                record.retryCount(),
                WalletMybatisSupport.toOffsetDateTime(record.nextRetryAt()),
                record.lastError()
        );
    }

    private String topicOf(String eventType) {
        return switch (eventType) {
            case "wallet.deposit.detected" -> "falconx.wallet.deposit.detected";
            case "wallet.deposit.confirmed" -> "falconx.wallet.deposit.confirmed";
            case "wallet.deposit.reversed" -> "falconx.wallet.deposit.reversed";
            case "wallet.withdraw.broadcast" -> "falconx.wallet.withdraw.broadcast";
            case "wallet.withdraw.confirmed" -> "falconx.wallet.withdraw.confirmed";
            case "wallet.withdraw.failed" -> "falconx.wallet.withdraw.failed";
            default -> throw new IllegalStateException("Unsupported wallet outbox event type: " + eventType);
        };
    }
}
