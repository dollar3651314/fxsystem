package com.falconx.infrastructure.kafka;

import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
import org.slf4j.MDC;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * Kafka 事件消息构造与 traceId 透传支持。
 *
 * <p>该工具类负责两件事：
 *
 * <ul>
 *   <li>统一构造 Kafka `Message<String>`，避免各服务重复拼接 header</li>
 *   <li>统一处理 Kafka 消费时的 `traceId` 注入与清理，保证日志自动带链路标识</li>
 * </ul>
 */
public final class KafkaEventMessageSupport {

    private KafkaEventMessageSupport() {
    }

    /**
     * 构造一条统一格式的 Kafka 文本事件消息。
     *
     * @param topic 目标 topic
     * @param key 分区键
     * @param payloadJson 已序列化的 JSON payload
     * @param eventId 事件唯一标识
     * @param eventType 事件类型
     * @param source 事件来源服务
     * @return 可直接交给 `KafkaTemplate` 发送的消息对象
     */
    public static Message<String> buildJsonMessage(String topic,
                                                   String key,
                                                   String payloadJson,
                                                   String eventId,
                                                   String eventType,
                                                   String source) {
        return MessageBuilder.withPayload(payloadJson)
                .setHeader(KafkaHeaders.TOPIC, topic)
                .setHeader(KafkaHeaders.KEY, key)
                .setHeader(KafkaEventHeaderConstants.EVENT_ID_HEADER, eventId)
                .setHeader(KafkaEventHeaderConstants.EVENT_TYPE_HEADER, eventType)
                .setHeader(KafkaEventHeaderConstants.EVENT_SOURCE_HEADER, source)
                .setHeader(KafkaEventHeaderConstants.TRACE_ID_HEADER, currentTraceId())
                .build();
    }

    /**
     * 把 Kafka 头中的 traceId 注入到当前线程 MDC。
     *
     * @param candidateTraceId Kafka 头里透传的 traceId
     * @return 当前消息链路实际使用的 traceId
     */
    public static String bindTraceId(String candidateTraceId) {
        String traceId = TraceIdSupport.reuseOrCreate(candidateTraceId);
        MDC.put(TraceIdConstants.TRACE_ID_MDC_KEY, traceId);
        return traceId;
    }

    /**
     * 清理当前线程绑定的 traceId。
     */
    public static void clearTraceId() {
        MDC.remove(TraceIdConstants.TRACE_ID_MDC_KEY);
    }

    /**
     * 获取当前线程 traceId；若当前线程还没有，则按统一规则补生成。
     *
     * @return 当前链路可用的 traceId
     */
    public static String currentTraceId() {
        return TraceIdSupport.reuseOrCreate(MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
    }
}
