package com.falconx.identity.api;

/**
 * STAGE-2-CUSTOMER：冻结/解冻成功响应。
 *
 * @param userId 客户主键
 * @param previousStatus 操作前状态
 * @param newStatus 操作后状态
 */
public record AdminFreezeResponse(long userId, String previousStatus, String newStatus) {
}
