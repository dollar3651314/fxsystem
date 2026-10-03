package com.falconx.market.entity;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 隔夜利息费率共享快照。
 *
 * <p>该对象不是 owner MySQL 表，而是 `market-service` 写入 Redis 的共享读模型，
 * 供 `trading-core-service` 在不跨服务查库的前提下，按结算日解析历史有效费率。
 */
public record MarketSwapRateSnapshot(
        String symbol,
        List<MarketSwapRateRule> rates,
        OffsetDateTime refreshedAt
) {
}
