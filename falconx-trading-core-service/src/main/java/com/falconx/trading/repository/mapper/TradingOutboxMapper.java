package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingOutboxRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 交易 Outbox MyBatis Mapper。
 *
 * <p>该 Mapper 负责 `t_outbox` 的 SQL 声明。
 */
@Mapper
public interface TradingOutboxMapper {

    /**
     * 新增或覆盖一条 Outbox 记录。
     *
     * @param record Outbox 记录
     * @return 影响行数
     */
    int upsertTradingOutbox(TradingOutboxRecord record);

    /**
     * 查询全部待发送事件。
     *
     * @return 待发送记录列表
     */
    List<TradingOutboxRecord> selectPending();

    /**
     * 查询可被当前调度批次认领的事件。
     *
     * @param now 当前时间
     * @param limit 批次大小
     * @return 可调度事件列表
     */
    List<TradingOutboxRecord> selectDispatchableBatch(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 把一条 Outbox 记录标记为调度中。
     *
     * @param id 主键 ID
     * @param updatedAt 更新时间
     * @return 影响行数
     */
    int updateDispatchingById(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 把一条 Outbox 记录标记为已发送。
     *
     * @param id 主键 ID
     * @param sentAt 发送时间
     * @return 影响行数
     */
    int updateSentById(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);

    /**
     * 把一条 Outbox 记录标记为失败或死信。
     *
     * @param id 主键 ID
     * @param statusCode 新状态码
     * @param retryCount 新重试次数
     * @param nextRetryAt 下一次重试时间
     * @param lastError 最近一次错误
     * @param updatedAt 更新时间
     * @return 影响行数
     */
    int updateFailedById(@Param("id") Long id,
                         @Param("statusCode") Integer statusCode,
                         @Param("retryCount") Integer retryCount,
                         @Param("nextRetryAt") LocalDateTime nextRetryAt,
                         @Param("lastError") String lastError,
                         @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 按主键查询 Outbox 记录。
     *
     * @param id 主键 ID
     * @return Outbox 记录
     */
    TradingOutboxRecord selectById(@Param("id") Long id);

    /**
     * 2026-05-21: 批量删除已发送且超过保留窗口的 outbox 记录，防止 t_outbox 无限增长。
     *
     * @param cutoff sent_at 早于此时间的记录会被删除
     * @param limit 单次最大删除行数
     * @return 实际删除行数
     */
    int deleteSentBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
