package com.falconx.common.api;

import java.time.OffsetDateTime;

/**
 * FalconX 对外统一响应对象。
 *
 * <p>当前阶段该类只提供最小骨架，用于在网关和后续服务 Controller 开发时保持统一响应结构。
 * 业务功能尚未落地前，不在此类中混入具体服务字段，只保留跨服务稳定复用的通用字段。
 *
 * @param <T> 业务数据载荷类型
 */
public record ApiResponse<T>(
        String code,
        String message,
        T data,
        OffsetDateTime timestamp,
        String traceId
) {

    /**
     * 创建一个成功响应对象。
     *
     * @param data 业务数据
     * @param traceId 当前请求链路标识
     * @param <T> 数据类型
     * @return 统一成功响应
     */
    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>("0", "success", data, OffsetDateTime.now(), traceId);
    }
}
