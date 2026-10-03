package com.falconx.trading.command;

import java.math.BigDecimal;

/**
 * 追加逐仓保证金命令。
 */
public record AddIsolatedMarginCommand(
        Long userId,
        Long positionId,
        BigDecimal amount
) {
}
