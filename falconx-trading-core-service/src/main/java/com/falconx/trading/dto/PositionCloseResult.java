package com.falconx.trading.dto;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingLiquidationLog;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingTrade;

/**
 * 手动平仓应用结果。
 *
 * <p>该对象把持仓终态、平仓成交和账户快照一起返回给 controller，
 * 避免 controller 再次回表拼装结果。
 */
public record PositionCloseResult(
        TradingPosition position,
        TradingTrade trade,
        TradingAccount account,
        TradingLiquidationLog liquidationLog
) {
}
