package com.falconx.market.dto;

import com.falconx.market.entity.MarketPriceStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 首页品种列表单项响应。
 *
 * <p>价格字段可为空；`priceStatus=MISSING` 时前端应展示无报价状态。
 */
public record MarketSymbolListItemResponse(
        String symbol,
        int category,
        String marketCode,
        String baseCurrency,
        String quoteCurrency,
        int pricePrecision,
        int qtyPrecision,
        BigDecimal minQty,
        BigDecimal maxQty,
        BigDecimal minNotional,
        int maxLeverage,
        BigDecimal takerFeeRate,
        BigDecimal spread,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal mid,
        BigDecimal mark,
        OffsetDateTime quoteTs,
        String quoteSource,
        MarketPriceStatus priceStatus,
        boolean tradable
) {
}
