package com.falconx.trading.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 成交实体。
 *
 * <p>该对象对应 `falconx_trading.t_trade` 的内存骨架表达，
 * 用于固化订单成交事实与手续费审计字段。
 */
public record TradingTrade(
        Long tradeId,
        Long orderId,
        Long positionId,
        Long userId,
        String symbol,
        TradingOrderSide side,
        TradingTradeType tradeType,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fee,
        BigDecimal realizedPnl,
        OffsetDateTime tradedAt
) {
}
