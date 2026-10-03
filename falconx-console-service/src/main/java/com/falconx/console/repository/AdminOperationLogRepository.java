package com.falconx.console.repository;

import com.falconx.console.repository.mapper.record.AdminOperationLogRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 管理操作审计日志 Repository。
 *
 * <p>写入由 OperationAuditAspect 调用 {@link #append}；
 * STAGE-9-RISK-OPS-COMPLETE §12.2 新增查询能力，由
 * AdminAuditLogApplicationService 在管理端审计页使用。
 */
public interface AdminOperationLogRepository {

    /**
     * 追加一条审计记录。
     */
    void append(long adminUserId,
                String permissionCode,
                String targetType,
                String targetId,
                String beforeValue,
                String afterValue,
                String riskLevel,
                String ip,
                String userAgent,
                OffsetDateTime occurredAt);

    /** 按主键查询单条审计记录。 */
    Optional<AdminOperationLogRecord> findById(long id);

    /** 按多条件分页查询审计记录列表（条件均为可选，传 null 即跳过该过滤）。 */
    List<AdminOperationLogRecord> findByFilters(Long adminUserId,
                                                 String permissionCode,
                                                 String targetType,
                                                 String targetId,
                                                 String riskLevel,
                                                 OffsetDateTime fromOccurredAt,
                                                 OffsetDateTime toOccurredAt,
                                                 int offset,
                                                 int limit);

    /** 按多条件统计审计记录总数（条件语义同 {@link #findByFilters}）。 */
    long countByFilters(Long adminUserId,
                         String permissionCode,
                         String targetType,
                         String targetId,
                         String riskLevel,
                         OffsetDateTime fromOccurredAt,
                         OffsetDateTime toOccurredAt);
}
