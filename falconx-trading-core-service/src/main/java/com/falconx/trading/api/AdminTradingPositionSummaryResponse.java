package com.falconx.trading.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 管理端持仓汇总（trading-core internal RPC）。
 *
 * <p>遍历全部 OPEN 持仓基于 quote snapshot 实时计算 markPrice + unrealizedPnl，
 * 汇总开放持仓数、占用保证金、未实现盈亏总额，供运营评估平台风险敞口。
 *
 * @param openPositionCount  当前 OPEN 持仓总条数
 * @param totalMarginUsed    OPEN 持仓占用保证金合计（USDT）
 * @param totalUnrealizedPnl OPEN 持仓未实现盈亏合计（USDT），缺 quote 的部分按 0 计入
 * @param positionsWithoutQuote 因没有 quote snapshot 无法计算 PnL 的持仓条数，用于运营定位空白报价
 * @param computedAt         汇总计算的时刻
 */
public record AdminTradingPositionSummaryResponse(
        long openPositionCount,
        BigDecimal totalMarginUsed,
        BigDecimal totalUnrealizedPnl,
        long positionsWithoutQuote,
        OffsetDateTime computedAt
) {
}
