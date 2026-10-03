package com.falconx.market.service;

import com.falconx.market.entity.KlineAggregationResult;
import com.falconx.market.entity.KlineSnapshot;
import com.falconx.market.entity.StandardQuote;

/**
 * K 线聚合服务。
 *
 * <p>该接口用于冻结市场服务内部的 K 线生成职责边界。
 * 当前实现允许同一条报价同时推进多个周期，因此返回值同时包含：
 *
 * <ul>
 *   <li>当前未收盘的 K 线快照（供北向 WebSocket 推送）</li>
 *   <li>本次报价触发收盘的 K 线快照（供 Outbox / ClickHouse 正式链路使用）</li>
 * </ul>
 */
public interface KlineAggregationService {

    /**
     * 用最新报价更新 K 线聚合状态。
     *
     * @param quote 标准报价对象
     * @return 当前报价推进后的 active / finalized K 线快照
     */
    KlineAggregationResult onQuote(StandardQuote quote);
}
