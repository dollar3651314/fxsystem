package com.falconx.console.repository.mapper;

import com.falconx.console.repository.mapper.record.AdminOperationLogRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code t_admin_operation_log} Mapper。
 *
 * <ul>
 *   <li>{@link #insert} 由 OperationAuditAspect 写入（阶段 1 落地）</li>
 *   <li>{@link #selectById} + {@link #selectByFilters} + {@link #countByFilters}：
 *       STAGE-9-RISK-OPS-COMPLETE §12.2 管理端查询</li>
 * </ul>
 */
@Mapper
public interface AdminOperationLogMapper {

    /** 写入一条审计日志。 */
    int insert(@Param("record") AdminOperationLogRecord record);

    /** STAGE-9 §12.2：按 id 查询。 */
    AdminOperationLogRecord selectById(@Param("id") Long id);

    /** STAGE-9 §12.2：多条件分页查询。 */
    List<AdminOperationLogRecord> selectByFilters(@Param("adminUserId") Long adminUserId,
                                                    @Param("permissionCode") String permissionCode,
                                                    @Param("targetType") String targetType,
                                                    @Param("targetId") String targetId,
                                                    @Param("riskLevel") String riskLevel,
                                                    @Param("fromOccurredAt") LocalDateTime fromOccurredAt,
                                                    @Param("toOccurredAt") LocalDateTime toOccurredAt,
                                                    @Param("offset") int offset,
                                                    @Param("limit") int limit);

    /** STAGE-9 §12.2：count，与 selectByFilters 过滤口径一致。 */
    long countByFilters(@Param("adminUserId") Long adminUserId,
                         @Param("permissionCode") String permissionCode,
                         @Param("targetType") String targetType,
                         @Param("targetId") String targetId,
                         @Param("riskLevel") String riskLevel,
                         @Param("fromOccurredAt") LocalDateTime fromOccurredAt,
                         @Param("toOccurredAt") LocalDateTime toOccurredAt);
}
