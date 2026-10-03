package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * STAGE-2-TRADING-MONITOR：管理端持仓汇总响应。
 *
 * <p>平台 OPEN 持仓未实现盈亏总额 + 占用保证金合计 + 持仓数；
 * 缺 quote 的持仓单独计数返回，便于运营定位空白报价。
 */
public record AdminTradingPositionSummaryResponse(
        long openPositionCount,
        BigDecimal totalMarginUsed,
        BigDecimal totalUnrealizedPnl,
        long positionsWithoutQuote,
        OffsetDateTime computedAt
) {
}
