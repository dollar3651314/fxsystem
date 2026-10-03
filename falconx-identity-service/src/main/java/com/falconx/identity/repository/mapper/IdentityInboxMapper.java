package com.falconx.identity.repository.mapper;

import com.falconx.identity.repository.mapper.record.IdentityInboxRecord;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * identity inbox MyBatis Mapper。
 *
 * <p>该 Mapper 只负责 `falconx_identity.t_inbox` 的 SQL 声明，
 * 不承担领域转换和业务幂等判断。
 */
@Mapper
public interface IdentityInboxMapper {

    /**
     * 查询指定事件是否已经被成功处理。
     *
     * @param eventId 事件唯一 ID
     * @return 已成功处理的记录数
     */
    Integer countProcessedByEventId(@Param("eventId") String eventId);

    /**
     * 插入一条已处理 inbox 记录。
     *
     * @param record inbox 持久化记录
     * @return 影响行数
     */
    int insertProcessed(IdentityInboxRecord record);

    /**
     * 删除已消费且早于 cutoff 的 inbox 行。LIMIT 防长事务。
     *
     * @param cutoff 截止时间
     * @param limit 单次最多删除行数
     * @return 实际删除行数
     */
    int deleteProcessedBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
