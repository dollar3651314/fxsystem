package com.falconx.identity.repository;

import java.time.OffsetDateTime;

/**
 * identity 关键事件收件箱仓储抽象。
 *
 * <p>该仓储用于承接低频关键事件的消费端幂等保护。
 * 当前阶段它服务 `falconx.trading.deposit.credited` 入金 credited 链路，
 * 让 identity-service 不会因为重复消息而重复处理同一入金事实。
 */
public interface IdentityInboxRepository {

    /**
     * 判断某条事件是否已经处理完成。
     *
     * @param eventId 事件唯一 ID
     * @return `true` 表示已处理
     */
    boolean existsProcessed(String eventId);

    /**
     * 记录一条已经成功处理的事件。
     *
     * @param eventId 事件唯一 ID
     * @param eventType 事件类型
     * @param source 来源服务
     * @param payload 事件 payload
     * @param consumedAt 消费完成时间
     */
    void saveProcessed(String eventId, String eventType, String source, Object payload, OffsetDateTime consumedAt);

    /**
     * 删除已消费且早于 cutoff 的 inbox 行。由 {@code IdentityInboxCleanupScheduler} 定时调用，
     * 防止历史去重记录无限累积。
     *
     * @param cutoff 截止时间
     * @param limit 单次最多删除行数
     * @return 实际删除行数
     */
    int deleteProcessedBefore(OffsetDateTime cutoff, int limit);
}
