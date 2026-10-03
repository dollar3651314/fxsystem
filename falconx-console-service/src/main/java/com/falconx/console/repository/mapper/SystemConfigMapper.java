package com.falconx.console.repository.mapper;

import com.falconx.console.repository.mapper.record.SystemConfigAuditRecord;
import com.falconx.console.repository.mapper.record.SystemConfigRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * STAGE-13 {@code t_system_config} + {@code t_system_config_audit} MyBatis Mapper。
 */
@Mapper
public interface SystemConfigMapper {

    /** 列出全部配置，按 category / config_key 排序。 */
    List<SystemConfigRecord> selectAll();

    /** 按 category 列出（admin UI 分组展示）。 */
    List<SystemConfigRecord> selectByCategory(@Param("category") String category);

    /** 单条精确查询。 */
    SystemConfigRecord selectByKey(@Param("configKey") String configKey);

    /**
     * 更新单条配置值；不存在则 INSERT。返回影响行数（INSERT=1, UPDATE=2 in MyBatis 默认行为）。
     */
    int upsertValue(@Param("configKey") String configKey,
                    @Param("configValue") String configValue,
                    @Param("updatedBy") Long updatedBy,
                    @Param("updatedAt") LocalDateTime updatedAt);

    /** 写一条审计日志（永不删）。 */
    int insertAudit(@Param("a") SystemConfigAuditRecord audit);

    /** 查询某 key 的近 N 条变更历史。 */
    List<SystemConfigAuditRecord> selectAuditByKey(@Param("configKey") String configKey,
                                                   @Param("limit") int limit);
}
