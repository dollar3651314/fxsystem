package com.falconx.trading.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * 交易 Inbox MyBatis 记录对象。
 *
 * <p>该记录对象用于承接 `t_inbox` 的去重落库字段。
 *
 * @param id 主键 ID
 * @param eventId 事件 ID
 * @param eventType 事件类型
 * @param source 来源服务
 * @param payloadJson JSON 字符串 payload
 * @param statusCode 状态码
 * @param consumedAt 消费完成时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record TradingInboxRecord(
        Long id,
        String eventId,
        String eventType,
        String source,
        String payloadJson,
        Integer statusCode,
        LocalDateTime consumedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
