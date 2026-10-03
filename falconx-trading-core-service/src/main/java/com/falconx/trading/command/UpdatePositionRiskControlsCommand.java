package com.falconx.trading.command;

import java.math.BigDecimal;

/**
 * 修改持仓 TP/SL 命令。
 *
 * @param userId 用户 ID
 * @param positionId 持仓 ID
 * @param takeProfitPriceProvided 是否显式传入了 takeProfitPrice 字段
 * @param takeProfitPrice 新的止盈价；显式传 `null` 表示清空
 * @param stopLossPriceProvided 是否显式传入了 stopLossPrice 字段
 * @param stopLossPrice 新的止损价；显式传 `null` 表示清空
 */
public record UpdatePositionRiskControlsCommand(
        Long userId,
        Long positionId,
        boolean takeProfitPriceProvided,
        BigDecimal takeProfitPrice,
        boolean stopLossPriceProvided,
        BigDecimal stopLossPrice
) {
}
