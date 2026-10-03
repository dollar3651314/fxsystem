package com.falconx.trading.dto;

import java.time.OffsetDateTime;

/**
 * STAGE-7-WITHDRAW Phase 2：用户白名单响应。
 *
 * <p>{@code status} 来自 wallet：{@code PENDING / ACTIVE / REMOVED}；{@code activatedAt}
 * 用于客户端渲染 24h 倒计时（status=PENDING 时 = createdAt + 24h，否则真实激活时间）。
 */
public record WithdrawWhitelistResponse(
        String whitelistId,
        String userId,
        String network,
        String address,
        String label,
        String status,
        OffsetDateTime activatedAt,
        OffsetDateTime createdAt
) {
}
