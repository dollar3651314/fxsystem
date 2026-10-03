package com.falconx.market.entity;

/**
 * 首页展示价格状态。
 */
public enum MarketPriceStatus {
    /**
     * Redis 短 TTL 最新价仍然新鲜，可作为交易链路候选价。
     */
    LIVE,

    /**
     * 展示用最后有效参考价，不允许用于成交。
     */
    REFERENCE,

    /**
     * 当前没有可展示价格。
     */
    MISSING
}
