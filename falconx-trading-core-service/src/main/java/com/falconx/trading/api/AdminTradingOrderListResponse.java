package com.falconx.trading.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端订单列表响应（trading-core internal RPC）。
 */
public record AdminTradingOrderListResponse(List<Item> items, long total, int page, int size) {

    public record Item(
            Long id,
            String orderNo,
            Long userId,
            String symbol,
            Integer side,
            Integer orderType,
            BigDecimal quantity,
            BigDecimal requestedPrice,
            BigDecimal filledPrice,
            BigDecimal leverage,
            BigDecimal margin,
            BigDecimal fee,
            BigDecimal openFeeRate,
            String clientOrderId,
            Integer status,
            String rejectReason,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            /** symbol 价格显示精度（来源 SymbolSpec.pricePrecision）；过渡期 SymbolSpec 缺失为 null，前端按缺省回退。 */
            Integer pricePrecision
    ) {
    }
}
