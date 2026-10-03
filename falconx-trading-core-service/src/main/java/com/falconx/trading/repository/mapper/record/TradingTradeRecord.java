package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易成交 MyBatis 记录对象。
 *
 * <p>该记录对象对应 `t_trade` 的数据库结构。
 *
 * @param id 主键 ID
 * @param orderId 订单 ID
 * @param positionId 持仓 ID
 * @param userId 用户 ID
 * @param symbol 品种
 * @param sideCode 成交方向码
 * @param tradeTypeCode 成交类型码
 * @param quantity 成交数量
 * @param price 成交价格
 * @param fee 手续费
 * @param realizedPnl 已实现盈亏
 * @param tradedAt 成交时间
 */
public record TradingTradeRecord(
        Long id,
        Long orderId,
        Long positionId,
        Long userId,
        String symbol,
        Integer sideCode,
        Integer tradeTypeCode,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fee,
        BigDecimal realizedPnl,
        LocalDateTime tradedAt
) {
}
