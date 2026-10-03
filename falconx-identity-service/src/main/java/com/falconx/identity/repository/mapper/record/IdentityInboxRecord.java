package com.falconx.identity.repository.mapper.record;

import java.time.LocalDateTime;

/**
 * identity inbox MyBatis 记录对象。
 *
 * <p>该记录对象只服务于 `t_inbox` 的持久化映射，
 * 与领域层事件对象解耦，避免上层直接感知数据库字段结构。
 *
 * @param id 主键 ID
 * @param eventId 事件唯一 ID
 * @param eventType 事件类型
 * @param source 来源服务
 * @param payloadJson JSON 字符串 payload
 * @param statusCode inbox 状态码
 * @param consumedAt 消费完成时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record IdentityInboxRecord(
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
