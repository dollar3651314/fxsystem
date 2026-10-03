package com.falconx.trading.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-7-WITHDRAW：出金单响应（POST / GET 详情 / GET 列表 item 共用）。
 *
 * <p>ID 字段统一以字符串返回（雪花 ID 防止前端精度丢失）。
 * 不回显 {@code whitelistId} 防止猜测他人白名单 ID。
 */
public record WithdrawOrderResponse(
        String withdrawId,
        String userId,
        BigDecimal amount,
        String currency,
        String network,
        String targetAddress,
        String status,
        OffsetDateTime coolingUntil,
        OffsetDateTime delayedUntil,
        String rejectReason,
        String txHash,
        int confirmations,
        String failureReason,
        OffsetDateTime createdAt
) {
}
