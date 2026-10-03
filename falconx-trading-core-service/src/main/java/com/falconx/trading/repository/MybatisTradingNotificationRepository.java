package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingNotification;
import com.falconx.trading.entity.TradingNotificationLevel;
import com.falconx.trading.entity.TradingNotificationStatus;
import com.falconx.trading.repository.mapper.TradingNotificationMapper;
import com.falconx.trading.repository.mapper.record.TradingNotificationRecord;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisTradingNotificationRepository implements TradingNotificationRepository {

    private final TradingNotificationMapper mapper;

    public MybatisTradingNotificationRepository(TradingNotificationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(TradingNotification n) {
        LocalDateTime created = n.createdAt() == null
                ? LocalDateTime.now(ZoneOffset.UTC)
                : n.createdAt().toLocalDateTime();
        TradingNotificationRecord record = new TradingNotificationRecord(
                n.id(),
                n.userId(),
                n.type(),
                n.templateCode(),
                n.level().code(),
                n.title(),
                n.body(),
                n.relatedKey(),
                n.relatedId(),
                n.payloadJson(),
                n.status().code(),
                n.readAt() == null ? null : n.readAt().toLocalDateTime(),
                created
        );
        mapper.insert(record);
    }

    @Override
    public Optional<TradingNotification> findById(Long id) {
        return Optional.ofNullable(toDomain(mapper.selectById(id)));
    }

    @Override
    public List<TradingNotification> findByUserId(Long userId, int offset, int limit) {
        return mapper.selectByUserId(userId, offset, limit).stream().map(this::toDomain).toList();
    }

    @Override
    public long countByUserId(Long userId) {
        return mapper.countByUserId(userId);
    }

    @Override
    public int countUnreadByUserId(Long userId) {
        return mapper.countUnreadByUserId(userId);
    }

    @Override
    public boolean markRead(Long id, Long userId) {
        return mapper.markRead(id, userId, LocalDateTime.now(ZoneOffset.UTC)) > 0;
    }

    @Override
    public int markAllRead(Long userId) {
        return mapper.markAllRead(userId, LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public boolean existsByRelated(String relatedKey, Long relatedId) {
        return mapper.countByRelated(relatedKey, relatedId) > 0;
    }

    @Override
    public List<TradingNotification> findByAdminFilters(Long userId, String type, String templateCode,
                                                         Integer levelCode, Integer statusCode,
                                                         LocalDateTime fromCreatedAt,
                                                         LocalDateTime toCreatedAt,
                                                         int offset, int limit) {
        return mapper.selectByAdminFilters(userId, type, templateCode, levelCode, statusCode,
                fromCreatedAt, toCreatedAt, offset, limit).stream().map(this::toDomain).toList();
    }

    @Override
    public long countByAdminFilters(Long userId, String type, String templateCode,
                                     Integer levelCode, Integer statusCode,
                                     LocalDateTime fromCreatedAt,
                                     LocalDateTime toCreatedAt) {
        return mapper.countByAdminFilters(userId, type, templateCode, levelCode, statusCode,
                fromCreatedAt, toCreatedAt);
    }

    private TradingNotification toDomain(TradingNotificationRecord r) {
        if (r == null) return null;
        return new TradingNotification(
                r.id(),
                r.userId(),
                r.type(),
                r.templateCode(),
                TradingNotificationLevel.fromCode(r.level() == null ? 1 : r.level()),
                r.title(),
                r.body(),
                r.relatedKey(),
                r.relatedId(),
                r.payloadJson(),
                TradingNotificationStatus.fromCode(r.status() == null ? 0 : r.status()),
                r.readAt() == null ? null : r.readAt().atOffset(ZoneOffset.UTC),
                r.createdAt() == null ? null : r.createdAt().atOffset(ZoneOffset.UTC)
        );
    }
}
