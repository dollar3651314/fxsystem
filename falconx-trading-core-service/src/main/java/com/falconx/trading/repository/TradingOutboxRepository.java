package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingOutboxMessage;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 交易核心 Outbox 仓储接口。
 *
 * <p>该接口用于在本地事务边界内保存低频关键待发送事件，
 * 后续再由调度器批量发送。
 */
public interface TradingOutboxRepository {

    /**
     * 保存待发送事件。
     *
     * @param message Outbox 事件
     * @return 持久化后的 Outbox 事件
     */
    TradingOutboxMessage save(TradingOutboxMessage message);

    /**
     * 查询尚未被调度器认领的待发送事件。
     *
     * @return 处于 `PENDING` 状态的事件列表
     */
    List<TradingOutboxMessage> findPending();

    /**
     * 认领一批可发送事件。
     *
     * <p>该方法用于模拟后续真实数据库里的 `FOR UPDATE SKIP LOCKED` 语义：
     * 只有满足“状态可发送且已到达下一次重试时间”的事件才会被选中，
     * 并在返回前先更新为 `DISPATCHING`，避免同一批事件被重复取出。
     *
     * @param now 当前时间，用于判断 `nextRetryAt`
     * @param limit 单批最大条数
     * @return 被当前调度器认领的事件批次
     */
    List<TradingOutboxMessage> claimDispatchableBatch(OffsetDateTime now, int limit);

    /**
     * 标记事件已发送。
     *
     * @param outboxId Outbox 主键
     * @param sentAt 发送时间
     */
    void markSent(String outboxId, OffsetDateTime sentAt);

    /**
     * 标记事件发送失败。
     *
     * @param outboxId Outbox 主键
     * @param nextRetryAt 下一次允许重试的时间
     * @param lastError 最近一次错误说明
     * @param maxRetryCount 最大自动重试次数
     */
    void markFailed(String outboxId, OffsetDateTime nextRetryAt, String lastError, int maxRetryCount);

    /**
     * 按主键查询一条 Outbox 记录。
     *
     * @param outboxId Outbox 主键
     * @return Outbox 记录
     */
    java.util.Optional<TradingOutboxMessage> findByOutboxId(String outboxId);

    /**
     * 2026-05-21: 批量删除已发送且早于 cutoff 的 outbox 记录。由 cleanup scheduler 调用。
     *
     * @param cutoff sent_at 早于此时间的记录会被删除
     * @param limit 单次最大删除行数（避免长事务）
     * @return 实际删除行数
     */
    int deleteSentBefore(java.time.OffsetDateTime cutoff, int limit);
}
