package com.falconx.market.service;

/**
 * 隔夜利息费率共享快照预热服务。
 */
public interface MarketSwapRateWarmupService {

    /**
     * 从 owner MySQL 全量刷新 Redis 共享快照。
     */
    void refreshAll();
}
