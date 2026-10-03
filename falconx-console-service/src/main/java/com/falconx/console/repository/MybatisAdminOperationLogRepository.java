package com.falconx.console.repository;

import com.falconx.console.repository.mapper.AdminOperationLogMapper;
import com.falconx.console.repository.mapper.record.AdminOperationLogRecord;
import com.falconx.infrastructure.id.IdGenerator;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisAdminOperationLogRepository implements AdminOperationLogRepository {

    private final AdminOperationLogMapper adminOperationLogMapper;
    private final IdGenerator idGenerator;

    public MybatisAdminOperationLogRepository(AdminOperationLogMapper adminOperationLogMapper,
                                              IdGenerator idGenerator) {
        this.adminOperationLogMapper = adminOperationLogMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public void append(long adminUserId,
                       String permissionCode,
                       String targetType,
                       String targetId,
                       String beforeValue,
                       String afterValue,
                       String riskLevel,
                       String ip,
                       String userAgent,
                       OffsetDateTime occurredAt) {
        AdminOperationLogRecord record = new AdminOperationLogRecord(
                idGenerator.nextId(),
                adminUserId,
                permissionCode,
                targetType,
                targetId,
                beforeValue,
                afterValue,
                riskLevel,
                ip,
                userAgent,
                occurredAt.toLocalDateTime()
        );
        adminOperationLogMapper.insert(record);
    }

    @Override
    public Optional<AdminOperationLogRecord> findById(long id) {
        return Optional.ofNullable(adminOperationLogMapper.selectById(id));
    }

    @Override
    public List<AdminOperationLogRecord> findByFilters(Long adminUserId, String permissionCode,
                                                        String targetType, String targetId,
                                                        String riskLevel,
                                                        OffsetDateTime fromOccurredAt,
                                                        OffsetDateTime toOccurredAt,
                                                        int offset, int limit) {
        return adminOperationLogMapper.selectByFilters(adminUserId, permissionCode,
                targetType, targetId, riskLevel,
                toLocal(fromOccurredAt), toLocal(toOccurredAt),
                offset, limit);
    }

    @Override
    public long countByFilters(Long adminUserId, String permissionCode,
                                String targetType, String targetId,
                                String riskLevel,
                                OffsetDateTime fromOccurredAt,
                                OffsetDateTime toOccurredAt) {
        return adminOperationLogMapper.countByFilters(adminUserId, permissionCode,
                targetType, targetId, riskLevel,
                toLocal(fromOccurredAt), toLocal(toOccurredAt));
    }

    private static LocalDateTime toLocal(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
