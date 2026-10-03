package com.falconx.market.analytics;

import com.falconx.market.entity.KlineSnapshot;
import com.falconx.market.entity.StandardQuote;

/**
 * ClickHouse 市场分析写入抽象。
 *
 * <p>该接口冻结了 `quote_tick` 与 `kline` 的写入职责，
 * 让应用层在 Stage 2A 就能拥有稳定的依赖方向，而不提前引入具体 ClickHouse 客户端实现。
 */
public interface MarketAnalyticsWriter {

    /**
     * 写入高频报价历史。
     *
     * @param quote 标准报价对象
     */
    void writeQuoteTick(StandardQuote quote);

    /**
     * 写入已聚合完成的 K 线快照。
     *
     * @param snapshot K 线快照
     */
    void writeKline(KlineSnapshot snapshot);
}
