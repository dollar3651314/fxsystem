package com.falconx.market.entity;

import java.util.List;

/**
 * 单条报价推进 K 线后的聚合结果。
 *
 * <p>北向 WebSocket 需要看到“当前未收盘 K 线”和“真正收盘 K 线”两种状态，
 * 因此内部聚合结果显式拆成 active / finalized 两部分。
 */
public record KlineAggregationResult(
        List<KlineSnapshot> activeSnapshots,
        List<KlineSnapshot> finalizedSnapshots
) {
}
