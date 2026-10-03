package com.falconx.console.repository.mapper;

import com.falconx.console.repository.mapper.record.AdminUserInfoRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 管理端通用「用户基本信息」批量查询。
 *
 * <p>跨 schema：依赖 console DB user 拥有 {@code falconx_identity} SELECT 权限
 * （沿用 {@code AdminCustomerMapper} / {@code AdminWithdrawEnrichmentMapper} 既有跨 schema 模式）。
 */
@Mapper
public interface AdminUserInfoEnrichmentMapper {

    /**
     * 按 userId 集合批查用户基本信息（一次 SQL 拿全部，list() 阶段避免 N+1）。
     *
     * @param userIds 去重后的用户 ID 集合；为空时调用方应直接跳过该方法
     * @return 每个存在的用户一行；不存在的 userId 无对应行
     */
    List<AdminUserInfoRecord> selectByUserIds(@Param("userIds") List<Long> userIds);
}
