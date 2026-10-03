package com.falconx.trading.entity;

/**
 * 对冲观测触发来源。
 *
 * <p>该枚举用于标记“哪一条 owner 链路导致本次净敞口观测被重算”，
 * 方便后续从 `t_hedge_log` 追溯是开仓、平仓还是行情刷新导致阈值变化。
 */
public enum TradingHedgeTriggerSource {
    OPEN_POSITION,
    MANUAL_CLOSE,
    TAKE_PROFIT,
    STOP_LOSS,
    LIQUIDATION,
    PRICE_TICK
}
