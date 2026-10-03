package com.falconx.market.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * market-service Outbox MyBatis 记录对象。
 *
 * @param id 主键
 * @param eventId 事件 ID
 * @param eventType 事件类型
 * @param topic 目标 topic
 * @param partitionKey 分区键
 * @param payloadJson JSON payload
 * @param statusCode 状态码
 * @param retryCount 重试次数
 * @param nextRetryAt 下一次重试时间
 * @param lastError 最近一次错误
 * @param createdAt 创建时间
 * @param sentAt 发送时间
 * @param updatedAt 更新时间
 */
public record MarketOutboxRecord(
        Long id,
        String eventId,
        String eventType,
        String topic,
        String partitionKey,
        String payloadJson,
        Integer statusCode,
        Integer retryCount,
        LocalDateTime nextRetryAt,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime sentAt,
        LocalDateTime updatedAt
) {
}
