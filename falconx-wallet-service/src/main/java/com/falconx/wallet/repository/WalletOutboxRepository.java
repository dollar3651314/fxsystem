package com.falconx.wallet.repository;

import com.falconx.wallet.entity.WalletOutboxMessage;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * wallet-service 发件箱仓储抽象。
 *
 * <p>该仓储负责把钱包低频关键事件正式落到 `t_outbox`，
 * 并为本地调度器提供认领、成功回写和失败退避所需的最小操作集合。
 */
public interface WalletOutboxRepository {

    /**
     * 保存一条出站事件。
     *
     * @param message 发件箱消息
     * @return 持久化后的消息
     */
    WalletOutboxMessage save(WalletOutboxMessage message);

    /**
     * 认领一批当前可调度的消息。
     *
     * @param now 当前时间
     * @param limit 批次大小
     * @return 已被当前调度批次认领的消息
     */
    List<WalletOutboxMessage> claimDispatchableBatch(OffsetDateTime now, int limit);

    /**
     * 将消息标记为已发送。
     *
     * @param outboxId Outbox 主键
     * @param sentAt 发送完成时间
     */
    void markSent(String outboxId, OffsetDateTime sentAt);

    /**
     * 将消息标记为失败或死信。
     *
     * @param outboxId Outbox 主键
     * @param nextRetryAt 下一次重试时间
     * @param lastError 最近一次错误
     * @param maxRetryCount 最大重试次数
     */
    void markFailed(String outboxId, OffsetDateTime nextRetryAt, String lastError, int maxRetryCount);

    /**
     * 按主键查询消息。
     *
     * @param outboxId Outbox 主键
     * @return 发件箱消息
     */
    Optional<WalletOutboxMessage> findByOutboxId(String outboxId);

    /**
     * 2026-05-21: 批量删除已发送且早于 cutoff 的 outbox 记录。由 cleanup scheduler 调用。
     *
     * @param cutoff sent_at 早于此时间的记录会被删除
     * @param limit 单次最大删除行数（避免长事务）
     * @return 实际删除行数
     */
    int deleteSentBefore(OffsetDateTime cutoff, int limit);
}
