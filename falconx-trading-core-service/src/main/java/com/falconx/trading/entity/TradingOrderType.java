package com.falconx.trading.entity;

/**
 * 订单类型枚举。
 *
 * <p>一期 Stage 3B 仅冻结市场单骨架，因此当前只保留 `MARKET`，
 * 限价和条件单相关状态会在后续阶段扩展。
 */
public enum TradingOrderType {
    MARKET
}
