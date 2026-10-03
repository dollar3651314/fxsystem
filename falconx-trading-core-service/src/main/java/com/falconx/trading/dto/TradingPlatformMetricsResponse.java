package com.falconx.trading.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Admin 仪表盘平台运营指标聚合响应。
 *
 * <p>4 段：实时持仓 / 平台收入 / 用户盈亏 / 资金流入流出 + 订单计数（5 段）。
 * 一次性返回所有数字；前端用 conic-gradient donut / horizontal bar + stat 卡渲染。
 */
public record TradingPlatformMetricsResponse(
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
            /** 用户口径累计平仓盈亏（>0 = 用户净盈利、平台净支付） */
            BigDecimal totalUserRealizedPnl
    ) {
    }

    public record RevenueBlock(
            BigDecimal feeIncomeAllTime,
            BigDecimal feeIncome30d,
            BigDecimal swapChargeAllTime,    // 平台向用户收取的 swap 累计（用户付出）
            BigDecimal swapIncomeAllTime,    // 平台向用户支付的 swap 累计（用户收入）
            BigDecimal swapNetForPlatformAllTime,  // swap_charge - swap_income，正 = 平台净 swap 收入
            BigDecimal swapCharge30d,
            BigDecimal swapIncome30d,
            BigDecimal swapNetForPlatform30d,
            /** 费用净收入 = fee + swap_net_for_platform（不含用户已实现盈亏冲销） */
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
            public static BiggestPosition empty() {
                return new BiggestPosition(null, null, BigDecimal.ZERO);
            }
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
