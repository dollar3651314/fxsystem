package com.falconx.market.service;

/**
 * 市场交易时间快照预热服务。
 *
 * <p>该服务负责把 `market-service` owner 的交易时间规则聚合后写入 Redis，
 * 供 `trading-core-service` 高频读取。
 */
public interface MarketTradingScheduleWarmupService {

    /**
     * 重新预热全部交易时间快照。
     */
    void refreshAll();
}
