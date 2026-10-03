package com.falconx.trading.websocket;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 管理端持仓汇总实时更新 payload。
 *
 * <p>QuoteDrivenEngine 每条 tick 触发（500ms 全局节流），仅推 tick 驱动的 totalUnrealizedPnl；
 * openPositionCount / totalMarginUsed 由 REST 端在持仓 lifecycle 事件（开仓 / 平仓 / 强平）
 * 后通过 query useEffect 刷新，不在 tick 路径里推送。
 *
 * <p>事件 type = "admin.position.summary"，channel = "admin.positions"（与 per-position
 * patch 同 channel，减少订阅数）。
 */
public record AdminPositionSummaryUpdatePayload(
        BigDecimal totalUnrealizedPnl,
        OffsetDateTime computedAt
) {
}
