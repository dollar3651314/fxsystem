package com.falconx.trading.entity;

/**
 * 订单状态枚举。
 *
 * <p>与状态机规范保持一致。市场单在本阶段通常直接落到 `FILLED` 或 `REJECTED`，
 * `PENDING` 和 `TRIGGERED` 预留给后续真实撮合前检查与条件单扩展。
 */
public enum TradingOrderStatus {
    PENDING,
    TRIGGERED,
    FILLED,
    CANCELLED,
    REJECTED
}
