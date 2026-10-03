package com.falconx.trading.entity;

/**
 * 订单方向枚举。
 *
 * <p>该枚举当前只在 trading-core-service 内部使用，
 * 用于区分买入开多和卖出开空两类最小市场单行为。
 */
public enum TradingOrderSide {
    BUY,
    SELL
}
