package com.falconx.trading.entity;

/**
 * 交易核心 Outbox 状态枚举。
 *
 * <p>该枚举直接对齐事务与幂等规范里的 5 态生命周期，
 * 避免把真实 `t_outbox` 需要具备的状态语义弱化成“待发送 / 已发送”两态模型。
 */
public enum TradingOutboxStatus {
    /**
     * 事务内刚写入，尚未被调度器认领。
     */
    PENDING,

    /**
     * 已被调度器认领，当前正在尝试发送。
     */
    DISPATCHING,

    /**
     * 已成功发送到下游消息系统。
     */
    SENT,

    /**
     * 发送失败，但仍允许按退避策略继续重试。
     */
    FAILED,

    /**
     * 已达到最大重试次数，不再自动发送。
     */
    DEAD
}
