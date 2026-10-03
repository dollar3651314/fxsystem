package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingInboxRecord;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 交易 Inbox MyBatis Mapper。
 *
 * <p>该 Mapper 只负责 `t_inbox` 的 SQL 声明，用于低频关键事件去重。
 */
@Mapper
public interface TradingInboxMapper {

    /**
     * 若事件不存在则插入一条已处理记录。
     *
     * @param record inbox 记录
     * @return 影响行数；1 表示首次写入，0 表示重复事件
     */
    int insertProcessedIfAbsent(TradingInboxRecord record);

    /**
     * 删除已消费且早于 cutoff 的 inbox 行。LIMIT 防长事务。
     *
     * <p>由 {@code TradingInboxCleanupScheduler} 定时调用；与 outbox cleanup 同款幂等：
     * 重复执行无副作用，依赖 INSERT IGNORE 的 event_id UNIQUE 约束保证消费侧重新入库不会冲突。
     *
     * @param cutoff 截止时间（UTC）
     * @param limit 单次最多删除行数
     * @return 实际删除行数
     */
    int deleteProcessedBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
