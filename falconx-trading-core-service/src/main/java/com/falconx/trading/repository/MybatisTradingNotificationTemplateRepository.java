package com.falconx.trading.repository;

import com.falconx.trading.entity.NotificationChannel;
import com.falconx.trading.entity.TradingNotificationLevel;
import com.falconx.trading.entity.TradingNotificationTemplate;
import com.falconx.trading.repository.mapper.TradingNotificationTemplateMapper;
import com.falconx.trading.repository.mapper.record.TradingNotificationTemplateRecord;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisTradingNotificationTemplateRepository implements TradingNotificationTemplateRepository {

    private final TradingNotificationTemplateMapper mapper;

    public MybatisTradingNotificationTemplateRepository(TradingNotificationTemplateMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(TradingNotificationTemplate t) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        mapper.insert(new TradingNotificationTemplateRecord(
                t.code(),
                t.titleTemplate(),
                t.bodyTemplate(),
                t.level().code(),
                channelsToCsv(t.channels()),
                t.description(),
                t.enabled() ? 1 : 0,
                t.createdAt() == null ? now : t.createdAt().toLocalDateTime(),
                t.updatedAt() == null ? now : t.updatedAt().toLocalDateTime()
        ));
    }

    @Override
    public Optional<TradingNotificationTemplate> findByCode(String code) {
        return Optional.ofNullable(toDomain(mapper.selectByCode(code)));
    }

    @Override
    public List<TradingNotificationTemplate> findPaginated(Boolean enabled, Integer levelCode, int offset, int limit) {
        Integer enabledInt = enabled == null ? null : (enabled ? 1 : 0);
        return mapper.selectPaginated(enabledInt, levelCode, offset, limit).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countFiltered(Boolean enabled, Integer levelCode) {
        Integer enabledInt = enabled == null ? null : (enabled ? 1 : 0);
        return mapper.countFiltered(enabledInt, levelCode);
    }

    @Override
    public boolean update(TradingNotificationTemplate t) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return mapper.update(new TradingNotificationTemplateRecord(
                t.code(),
                t.titleTemplate(),
                t.bodyTemplate(),
                t.level().code(),
                channelsToCsv(t.channels()),
                t.description(),
                t.enabled() ? 1 : 0,
                null,
                now
        )) > 0;
    }

    @Override
    public boolean softDelete(String code) {
        return mapper.softDelete(code, LocalDateTime.now(ZoneOffset.UTC)) > 0;
    }

    private TradingNotificationTemplate toDomain(TradingNotificationTemplateRecord r) {
        if (r == null) return null;
        return new TradingNotificationTemplate(
                r.code(),
                r.titleTemplate(),
                r.bodyTemplate(),
                TradingNotificationLevel.fromCode(r.level() == null ? 1 : r.level()),
                csvToChannels(r.channels()),
                r.description(),
                r.enabled() != null && r.enabled() == 1,
                r.createdAt() == null ? null : r.createdAt().atOffset(ZoneOffset.UTC),
                r.updatedAt() == null ? null : r.updatedAt().atOffset(ZoneOffset.UTC)
        );
    }

    private static String channelsToCsv(List<NotificationChannel> channels) {
        if (channels == null || channels.isEmpty()) return NotificationChannel.IN_APP.name();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < channels.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(channels.get(i).name());
        }
        return sb.toString();
    }

    private static List<NotificationChannel> csvToChannels(String csv) {
        if (csv == null || csv.isBlank()) return List.of(NotificationChannel.IN_APP);
        List<NotificationChannel> result = new ArrayList<>();
        for (String part : Arrays.stream(csv.split(",")).map(String::trim).toList()) {
            NotificationChannel ch = NotificationChannel.fromName(part);
            if (ch != null) result.add(ch);
        }
        return result.isEmpty() ? List.of(NotificationChannel.IN_APP) : result;
    }
}
