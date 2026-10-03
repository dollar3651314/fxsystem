package com.falconx.trading.dto;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingPosition;

/**
 * 追加逐仓保证金结果。
 */
public record AddIsolatedMarginResult(
        TradingPosition position,
        TradingAccount account
) {
}
