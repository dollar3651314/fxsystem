package com.falconx.console.audit;

import com.falconx.console.api.AdminAuditLogItem;
import com.falconx.console.api.AdminAuditLogListResponse;
import com.falconx.console.error.AdminBusinessException;
import com.falconx.console.error.AdminErrorCode;
import com.falconx.console.repository.AdminOperationLogRepository;
import com.falconx.console.repository.mapper.record.AdminOperationLogRecord;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * STAGE-9-RISK-OPS-COMPLETE §12.2：审计日志查询应用服务。
 *
 * <p>console-service 端自持 t_admin_operation_log（owner = falconx_console schema），
 * 无 trading-core internal RPC，查询链路简化为：
 * console-frontend → console-service → Repository → mapper。
 */
@Service
public class AdminAuditLogApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditLogApplicationService.class);

    private final AdminOperationLogRepository repository;

    public AdminAuditLogApplicationService(AdminOperationLogRepository repository) {
        this.repository = repository;
    }

    public AdminAuditLogListResponse list(Long adminUserId, String permissionCode,
                                           String targetType, String targetId,
                                           String riskLevel,
                                           OffsetDateTime fromOccurredAt,
                                           OffsetDateTime toOccurredAt,
                                           int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        List<AdminOperationLogRecord> records = repository.findByFilters(
                adminUserId, permissionCode, targetType, targetId, riskLevel,
                fromOccurredAt, toOccurredAt,
                (safePage - 1) * safeSize, safeSize);
        long total = repository.countByFilters(adminUserId, permissionCode,
                targetType, targetId, riskLevel, fromOccurredAt, toOccurredAt);
        List<AdminAuditLogItem> items = records.stream()
                .map(AdminAuditLogApplicationService::toItem)
                .toList();
        return new AdminAuditLogListResponse(safePage, safeSize, total, items);
    }

    public AdminAuditLogItem detail(long id) {
        Optional<AdminOperationLogRecord> record = repository.findById(id);
        if (record.isEmpty()) {
            throw new AdminBusinessException(AdminErrorCode.ADMIN_AUDIT_LOG_NOT_FOUND);
        }
        return toItem(record.get());
    }

    private static AdminAuditLogItem toItem(AdminOperationLogRecord r) {
        return new AdminAuditLogItem(
                r.id() == null ? null : String.valueOf(r.id()),
                r.adminUserId() == null ? null : String.valueOf(r.adminUserId()),
                r.permissionCode(),
                r.targetType(),
                r.targetId(),
                r.beforeValue(),
                r.afterValue(),
                r.riskLevel(),
                r.ip(),
                r.userAgent(),
                r.occurredAt() == null ? null : r.occurredAt().atOffset(ZoneOffset.UTC)
        );
    }
}
