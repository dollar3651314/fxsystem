package com.falconx.trading.entity;

/**
 * 持仓终态原因。
 *
 * <p>本轮只真正实现 `MANUAL`，其余枚举值用于冻结 TP/SL 与强平的持久化编码。
 */
public enum TradingPositionCloseReason {
    MANUAL,
    TAKE_PROFIT,
    STOP_LOSS,
    LIQUIDATION,
    // STAGE-14D2 Task 1：CROSS 账户级强平（marginLevel ≤ stopOut 触发，单仓 liquidationPrice=null）。
    // 与 LIQUIDATION 同口径落账（LIQUIDATED 状态 + biz_type=9），区别在触发源为账户级而非单仓价格。
    CROSS_STOP_OUT
}
