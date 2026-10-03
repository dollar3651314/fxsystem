package com.falconx.infrastructure.kafka;

import com.falconx.infrastructure.trace.TraceIdConstants;

/**
 * Kafka 事件头常量。
 *
 * <p>该常量类用于统一 FalconX 内部 Kafka 事件在消息头上的最小字段集合，
 * 避免各服务在 producer / consumer 中散落硬编码字符串。
 *
 * <p>当前阶段统一约定：
 *
 * <ul>
 *   <li>`eventId` 通过消息头透传，供低频关键事件做幂等</li>
 *   <li>`eventType` 通过消息头透传，供消费者记录审计和故障定位</li>
 *   <li>`source` 通过消息头透传，供跨服务排障</li>
 *   <li>`traceId` 沿用现有 `X-Trace-Id` 规则透传</li>
 * </ul>
 */
public final class KafkaEventHeaderConstants {

    /**
     * Kafka 事件唯一标识头。
     */
    public static final String EVENT_ID_HEADER = "X-Event-Id";

    /**
     * Kafka 事件类型头。
     */
    public static final String EVENT_TYPE_HEADER = "X-Event-Type";

    /**
     * Kafka 事件来源头。
     */
    public static final String EVENT_SOURCE_HEADER = "X-Event-Source";

    /**
     * Kafka 内部继续沿用统一的 traceId 头名称。
     */
    public static final String TRACE_ID_HEADER = TraceIdConstants.TRACE_ID_HEADER;

    private KafkaEventHeaderConstants() {
    }
}
