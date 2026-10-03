package com.falconx.infrastructure.trace;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * TraceId 支撑工具。
 *
 * <p>该工具类统一承担 FalconX 一期 `traceId` 的生成与校验职责，
 * 供 gateway 和下游服务入口过滤器复用，避免每个服务各自发明格式规则。
 */
public final class TraceIdSupport {

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

    private TraceIdSupport() {
    }

    /**
     * 生成新的系统级 traceId。
     *
     * @return `32` 位小写十六进制 traceId
     */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 校验给定字符串是否符合 FalconX 的 traceId 规则。
     *
     * @param traceId 待校验 traceId
     * @return `true` 表示合法
     */
    public static boolean isValid(String traceId) {
        return traceId != null && TRACE_ID_PATTERN.matcher(traceId).matches();
    }

    /**
     * 若传入值合法则直接使用，否则生成新的 traceId。
     *
     * <p>该方法用于下游服务继续沿用 gateway 传入的系统内 traceId，
     * 同时避免使用无效值。
     *
     * @param candidate 候选 traceId
     * @return 合法 traceId
     */
    public static String reuseOrCreate(String candidate) {
        return isValid(candidate) ? candidate : newTraceId();
    }
}
