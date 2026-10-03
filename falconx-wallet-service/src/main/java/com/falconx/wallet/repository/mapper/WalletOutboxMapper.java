package com.falconx.wallet.repository.mapper;

import com.falconx.wallet.repository.mapper.record.WalletOutboxRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * wallet-service Outbox MyBatis Mapper。
 *
 * <p>该 Mapper 只负责 `t_outbox` 的 SQL 声明，
 * 不承载业务决策和状态机编排逻辑。
 */
@Mapper
public interface WalletOutboxMapper {

    /**
     * 新增或覆盖一条 Outbox 记录。
     *
     * @param record Outbox 记录
     * @return 影响行数
     */
    int upsertWalletOutbox(WalletOutboxRecord record);

    /**
     * 查询可被当前调度批次认领的消息。
     *
     * @param now 当前时间
     * @param limit 批次大小
     * @return 可调度记录列表
     */
    List<WalletOutboxRecord> selectDispatchableBatch(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 将一条消息标记为调度中。
     *
     * @param id 主键 ID
     * @param updatedAt 更新时间
     * @return 影响行数
     */
    int updateDispatchingById(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 将一条消息标记为已发送。
     *
     * @param id 主键 ID
     * @param sentAt 发送时间
     * @return 影响行数
     */
    int updateSentById(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);

    /**
     * 将一条消息标记为失败或死信。
     *
     * @param id 主键 ID
     * @param statusCode 新状态码
     * @param retryCount 重试次数
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
    WalletOutboxRecord selectById(@Param("id") Long id);

    /**
     * 2026-05-21: 批量删除已发送且超过保留窗口的 outbox 记录。
     *
     * @param cutoff sent_at 早于此时间的记录会被删除
     * @param limit 单次最大删除行数
     * @return 实际删除行数
     */
    int deleteSentBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
