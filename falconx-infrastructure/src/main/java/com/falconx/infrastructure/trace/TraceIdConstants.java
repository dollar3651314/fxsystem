package com.falconx.infrastructure.trace;

/**
 * TraceId 相关的统一常量定义。
 *
 * <p>当前常量用于冻结跨服务透传时的字段名，
 * 后续网关过滤器、HTTP 客户端拦截器、Kafka 生产消费组件都应复用这些常量，避免字符串散落。
 */
public final class TraceIdConstants {

    /**
     * HTTP 响应头与内部透传头统一使用该键名。
     */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * 日志上下文字段名统一使用该键名。
     */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    private TraceIdConstants() {
    }
}
