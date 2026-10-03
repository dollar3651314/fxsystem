package com.falconx.market.entity;

/**
 * market-service Outbox 状态枚举。
 *
 * <p>该枚举用于表达 `market.kline.update` 等低频关键事件
 * 在本地发件箱中的发送进度与失败退避状态。
 */
public enum MarketOutboxStatus {
    PENDING,
    DISPATCHING,
    SENT,
    FAILED,
    DEAD
}
