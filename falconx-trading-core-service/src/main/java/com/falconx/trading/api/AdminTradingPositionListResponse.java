package com.falconx.trading.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端持仓列表响应（trading-core internal RPC）。
 */
public record AdminTradingPositionListResponse(List<Item> items, long total, int page, int size) {

    public record Item(
            Long id,
            Long openingOrderId,
            Long userId,
            String symbol,
            Integer side,
            BigDecimal quantity,
            BigDecimal entryPrice,
            BigDecimal leverage,
            BigDecimal margin,
            Integer marginMode,
            BigDecimal liquidationPrice,
            BigDecimal takeProfitPrice,
            BigDecimal stopLossPrice,
            BigDecimal closePrice,
            Integer closeReason,
            BigDecimal realizedPnl,
            BigDecimal openFeeRate,
            Integer status,
            LocalDateTime openedAt,
            LocalDateTime closedAt,
            LocalDateTime updatedAt,
            /** OPEN 持仓基于 quote snapshot 计算的实时 markPrice；非 OPEN 或缺 quote 为 null */
            BigDecimal markPrice,
            /** OPEN 持仓基于 markPrice 计算的浮动盈亏；非 OPEN 或缺 quote 为 null */
            BigDecimal unrealizedPnl,
            /** symbol 价格显示精度（来源 SymbolSpec.pricePrecision）；过渡期 SymbolSpec 缺失为 null，前端按缺省回退。 */
            Integer pricePrecision
    ) {
    }
}
