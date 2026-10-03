package com.falconx.market.repository;

import com.falconx.market.entity.MarketOutboxMessage;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * market-service 发件箱仓储抽象。
 *
 * <p>该仓储负责把低频市场事件正式落到 `t_outbox`，
 * 并为调度器提供认领、成功回写与失败退避操作。
 */
public interface MarketOutboxRepository {

    /**
     * 保存一条发件箱消息。
     *
     * @param message 发件箱消息
     * @return 持久化后的消息
     */
    MarketOutboxMessage save(MarketOutboxMessage message);

    /**
     * 认领一批当前可调度的消息。
     *
     * @param now 当前时间
     * @param limit 批次大小
     * @return 已认领的消息列表
     */
    List<MarketOutboxMessage> claimDispatchableBatch(OffsetDateTime now, int limit);

    /**
     * 将消息标记为已发送。
     *
     * @param outboxId 主键
     * @param sentAt 发送时间
     */
    void markSent(String outboxId, OffsetDateTime sentAt);

    /**
     * 将消息标记为失败或死信。
     *
     * @param outboxId 主键
     * @param nextRetryAt 下一次重试时间
     * @param lastError 最近一次错误
     * @param maxRetryCount 最大重试次数
     */
    void markFailed(String outboxId, OffsetDateTime nextRetryAt, String lastError, int maxRetryCount);

    /**
     * 按主键查询消息。
     *
     * @param outboxId 主键
     * @return 发件箱消息
     */
    Optional<MarketOutboxMessage> findByOutboxId(String outboxId);

    /**
     * 批量删除已发送且早于 cutoff 的消息，防止 t_outbox 无限增长。
     * 由 {@code MarketOutboxCleanupScheduler} 每小时调用。
     *
     * @param cutoff sent_at 早于此时间的记录会被删除
     * @param limit 单次最大删除行数（避免长事务）
     * @return 实际删除行数
     */
    int deleteSentBefore(OffsetDateTime cutoff, int limit);
}
