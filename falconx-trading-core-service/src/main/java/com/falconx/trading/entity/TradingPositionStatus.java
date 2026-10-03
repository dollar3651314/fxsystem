package com.falconx.trading.entity;

/**
 * 持仓状态枚举。
 *
 * <p>该枚举用于表达持仓是否仍在占用保证金，
 * 以及后续是否由普通平仓还是强平结束。
 */
public enum TradingPositionStatus {
    OPEN,
    CLOSED,
    LIQUIDATED
}
