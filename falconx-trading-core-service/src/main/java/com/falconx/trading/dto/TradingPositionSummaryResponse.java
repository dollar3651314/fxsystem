package com.falconx.trading.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 用户持仓汇总（GET /api/v1/trading/positions/summary）。
 *
 * <p>遍历当前用户的全部 OPEN 持仓 + 实时 quote snapshot 即时计算总未实现盈亏，
 * 用于 dashboard / market 顶部「未实现盈亏」卡片的初始值，之后由 WS 的
 * {@code user.position.summary} 事件持续刷新。
 *
 * @param openPositionCount      OPEN 持仓笔数
 * @param totalMarginUsed        OPEN 持仓占用保证金合计（USDT）
 * @param totalUnrealizedPnl     OPEN 持仓未实现盈亏合计（USDT）；缺 quote 的持仓按 0 计入
 * @param positionsWithoutQuote  因没有 quote snapshot 无法计算 PnL 的持仓笔数
 * @param computedAt             汇总计算的时刻
 */
public record TradingPositionSummaryResponse(
        long openPositionCount,
        BigDecimal totalMarginUsed,
        BigDecimal totalUnrealizedPnl,
        long positionsWithoutQuote,
        OffsetDateTime computedAt
) {
}
