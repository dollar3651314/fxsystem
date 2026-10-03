package com.falconx.trading.entity;

/**
 * 成交类型。
 *
 * <p>当前真实实现只覆盖开仓与手动平仓；`LIQUIDATION` 仍为后续阶段预留。
 */
public enum TradingTradeType {
    OPEN,
    CLOSE,
    LIQUIDATION
}
