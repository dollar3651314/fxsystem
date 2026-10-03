package com.falconx.trading.dto;

import java.math.BigDecimal;

/**
 * 市价单响应 DTO。
 *
 * <p>该对象用于把应用层的订单、持仓、成交和账户结果
 * 收敛成网关和外部调用方更容易消费的结构化视图。
 * 当前额外返回持仓级 TP/SL 价格，便于客户端确认触发价已随开仓一并写入。
 */
public record PlaceMarketOrderResponse(
        String orderNo,
        String orderStatus,
        String rejectionReason,
        boolean duplicate,
        String symbol,
        String side,
        BigDecimal quantity,
        BigDecimal requestPrice,
        BigDecimal filledPrice,
        BigDecimal leverage,
        String marginMode,
        BigDecimal margin,
        BigDecimal fee,
        Long positionId,
        String positionStatus,
        BigDecimal takeProfitPrice,
        BigDecimal stopLossPrice,
        Long tradeId,
        TradingAccountResponse account
) {
}
