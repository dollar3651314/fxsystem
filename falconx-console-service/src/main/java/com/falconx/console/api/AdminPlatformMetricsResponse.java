package com.falconx.console.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Admin 仪表盘平台运营指标聚合（与 trading-core {@code TradingPlatformMetricsResponse} 一一对齐）。
 */
public record AdminPlatformMetricsResponse(
        PositionsBlock positions,
        RevenueBlock revenue,
        UserPnlBlock userPnl,
        FlowsBlock flows,
        OrdersBlock orders,
        OffsetDateTime computedAt
) {
    public record PositionsBlock(
            long openCount,
            long longCount,
            long shortCount,
            long closedCount,
            long liquidatedCount,
            BigDecimal totalUserRealizedPnl
    ) {
    }

    public record RevenueBlock(
            BigDecimal feeIncomeAllTime,
            BigDecimal feeIncome30d,
            BigDecimal swapChargeAllTime,
            BigDecimal swapIncomeAllTime,
            BigDecimal swapNetForPlatformAllTime,
            BigDecimal swapCharge30d,
            BigDecimal swapIncome30d,
            BigDecimal swapNetForPlatform30d,
            BigDecimal platformFeeRevenueAllTime,
            BigDecimal platformFeeRevenue30d
    ) {
    }

    public record UserPnlBlock(
            long winningUsers,
            long losingUsers,
            long evenUsers,
            BiggestPosition biggestWinner,
            BiggestPosition biggestLoser
    ) {
        public record BiggestPosition(
                Long userId,
                String symbol,
                BigDecimal amount
        ) {
        }
    }

    public record FlowsBlock(
            BigDecimal totalDepositAllTime,
            BigDecimal totalDeposit30d,
            BigDecimal totalWithdrawAllTime,
            BigDecimal totalWithdraw30d,
            BigDecimal netFlowAllTime,
            BigDecimal netFlow30d
    ) {
    }

    public record OrdersBlock(
            long totalFilledOrders,
            long filledOrdersToday
    ) {
    }
}
