package com.falconx.trading.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * 交易 Outbox MyBatis 记录对象。
 *
 * <p>该记录对象对应 `t_outbox` 的数据库结构。
 *
 * @param id 主键 ID
 * @param eventId 事件 ID
 * @param eventType 事件类型
 * @param topic 目标 Topic
 * @param partitionKey 分区键
 * @param payloadJson JSON 字符串 payload
 * @param statusCode Outbox 状态码
 * @param retryCount 重试次数
 * @param nextRetryAt 下一次重试时间
 * @param lastError 最近一次错误
 * @param createdAt 创建时间
 * @param sentAt 发送成功时间
 * @param updatedAt 更新时间
 */
public record TradingOutboxRecord(
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
