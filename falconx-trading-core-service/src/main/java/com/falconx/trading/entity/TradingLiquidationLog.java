package com.falconx.trading.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 强平日志实体。
 *
 * <p>该对象对应 `t_liquidation_log`，用于冻结强平执行时的审计事实：
 * 触发价、标记价、真实亏损、释放保证金以及平台兜底金额都必须在同一本地事务内落库。
 */
public record TradingLiquidationLog(
        Long liquidationLogId,
        Long userId,
        Long positionId,
        String symbol,
        TradingOrderSide side,
        TradingMarginMode marginMode,
        BigDecimal quantity,
        BigDecimal entryPrice,
        BigDecimal liquidationPrice,
        BigDecimal markPrice,
        OffsetDateTime priceTs,
        String priceSource,
        BigDecimal loss,
        BigDecimal fee,
        BigDecimal marginReleased,
        BigDecimal platformCoveredLoss,
        OffsetDateTime createdAt
) {
}
