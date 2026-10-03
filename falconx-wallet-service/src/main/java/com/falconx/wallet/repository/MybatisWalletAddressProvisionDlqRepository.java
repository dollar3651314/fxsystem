package com.falconx.wallet.repository;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.wallet.entity.WalletAddressProvisionDlqEntry;
import com.falconx.wallet.entity.WalletAddressProvisionDlqStatus;
import com.falconx.wallet.repository.mapper.WalletAddressProvisionDlqMapper;
import com.falconx.wallet.repository.mapper.record.WalletAddressProvisionDlqRecord;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisWalletAddressProvisionDlqRepository implements WalletAddressProvisionDlqRepository {

    private final WalletAddressProvisionDlqMapper mapper;
    private final IdGenerator idGenerator;

    public MybatisWalletAddressProvisionDlqRepository(WalletAddressProvisionDlqMapper mapper,
                                                       IdGenerator idGenerator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public void recordFailure(WalletAddressProvisionDlqEntry entry) {
        // event_id 已存在 PENDING 行 → 仅累加重试次数 + 覆盖最新错误
        int updated = mapper.upsertOnDuplicate(
                entry.eventId(),
                entry.lastErrorCode(),
                entry.lastErrorMessage(),
                entry.lastAttemptAt() == null ? LocalDateTime.now(ZoneOffset.UTC) : entry.lastAttemptAt().toLocalDateTime()
        );
        if (updated > 0) return;
        // 否则首次入库
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        mapper.insert(new WalletAddressProvisionDlqRecord(
                idGenerator.nextId(),
                entry.eventId(),
                entry.userId(),
                entry.uid(),
                entry.email(),
                entry.attemptCount() <= 0 ? 1 : entry.attemptCount(),
                WalletAddressProvisionDlqStatus.PENDING.code(),
                entry.lastErrorCode(),
                entry.lastErrorMessage(),
                entry.lastAttemptAt() == null ? now : entry.lastAttemptAt().toLocalDateTime(),
                null,
                now
        ));
    }

    @Override
    public Optional<WalletAddressProvisionDlqEntry> findById(long id) {
        return Optional.ofNullable(toDomain(mapper.selectById(id)));
    }

    @Override
    public Optional<WalletAddressProvisionDlqEntry> findByEventId(String eventId) {
        return Optional.ofNullable(toDomain(mapper.selectByEventId(eventId)));
    }

    @Override
    public List<WalletAddressProvisionDlqEntry> findPaginated(Integer statusCode, Long userId, int offset, int limit) {
        return mapper.selectPaginated(statusCode, userId, offset, limit).stream().map(this::toDomain).toList();
    }

    @Override
    public long countFiltered(Integer statusCode, Long userId) {
        return mapper.countFiltered(statusCode, userId);
    }

    @Override
    public boolean markResolved(long id) {
        return mapper.markResolved(id, LocalDateTime.now(ZoneOffset.UTC)) > 0;
    }

    private WalletAddressProvisionDlqEntry toDomain(WalletAddressProvisionDlqRecord r) {
        if (r == null) return null;
        return new WalletAddressProvisionDlqEntry(
                r.id(),
                r.eventId(),
                r.userId(),
                r.uid(),
                r.email(),
                r.attemptCount() == null ? 0 : r.attemptCount(),
                WalletAddressProvisionDlqStatus.fromCode(r.status() == null ? 0 : r.status()),
                r.lastErrorCode(),
                r.lastErrorMessage(),
                r.lastAttemptAt() == null ? null : r.lastAttemptAt().atOffset(ZoneOffset.UTC),
                r.resolvedAt() == null ? null : r.resolvedAt().atOffset(ZoneOffset.UTC),
                r.createdAt() == null ? null : r.createdAt().atOffset(ZoneOffset.UTC)
        );
    }
}
