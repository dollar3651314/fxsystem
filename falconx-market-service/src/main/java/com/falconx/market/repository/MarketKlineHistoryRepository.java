package com.falconx.market.repository;

import com.falconx.market.entity.KlineSnapshot;
import java.util.List;

/**
 * market-service K 线历史仓储。
 *
 * <p>历史 K 线由 market owner 写入 ClickHouse，本仓储只提供 owner 内部查询。
 */
public interface MarketKlineHistoryRepository {

    List<KlineSnapshot> findRecentKlines(String symbol, String interval, int limit);
}
