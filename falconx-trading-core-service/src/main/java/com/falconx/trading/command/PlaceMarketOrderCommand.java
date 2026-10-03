package com.falconx.trading.command;

import com.falconx.trading.entity.TradingMarginMode;
import com.falconx.trading.entity.TradingOrderSide;
import java.math.BigDecimal;

/**
 * 市价单下单命令。
 *
 * <p>当前阶段该命令先只覆盖最小开仓输入：
 * 用户、品种、方向、数量、杠杆、持仓级 TP/SL 和 `clientOrderId`。
 */
public record PlaceMarketOrderCommand(
        Long userId,
        String symbol,
        TradingOrderSide side,
        BigDecimal quantity,
        BigDecimal leverage,
        TradingMarginMode marginMode,
        BigDecimal takeProfitPrice,
        BigDecimal stopLossPrice,
        String clientOrderId,
        /**
         * STAGE-12-GROUP-MARKUP: 用户所属组（来自 gateway 透传的 X-User-Group-Code header）。
         * 用于在撮合时查询 t_symbol_group_markup 应用组级加点；null/blank 按 "default" 处理。
         * 放在字段末尾以最小化对现有 caller 的破坏（兼容追加而非中间插入）。
         */
        String groupCode
) {
    /**
     * STAGE-12-GROUP-MARKUP：向后兼容 9-arg 构造器，groupCode 默认 null（按 "default" 处理）。
     * 测试 fixture 不需要全部加上 groupCode 参数。
     */
    public PlaceMarketOrderCommand(
            Long userId,
            String symbol,
            TradingOrderSide side,
            BigDecimal quantity,
            BigDecimal leverage,
            TradingMarginMode marginMode,
            BigDecimal takeProfitPrice,
            BigDecimal stopLossPrice,
            String clientOrderId) {
        this(userId, symbol, side, quantity, leverage, marginMode,
                takeProfitPrice, stopLossPrice, clientOrderId, null);
    }

    /**
     * 返回 groupCode，null/blank 兜底 "default"。
     */
    public String resolvedGroupCode() {
        return (groupCode == null || groupCode.isBlank()) ? "default" : groupCode.trim();
    }
}
