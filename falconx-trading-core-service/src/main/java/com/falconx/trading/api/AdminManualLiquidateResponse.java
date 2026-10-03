package com.falconx.trading.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 管理端手动强平响应。
 */
public record AdminManualLiquidateResponse(
        Long positionId,
        Long liquidationLogId,
        OffsetDateTime closedAt,
        BigDecimal realizedPnl
) {
}
