package com.falconx.market.repository;

import com.falconx.market.entity.MarketTradingScheduleSnapshot;
import java.util.Optional;

/**
 * 交易时间快照仓储。
 *
 * <p>该仓储负责把 `market-service` owner 的交易时间规则聚合后写入 Redis，
 * 供下游服务按 `symbol` 读取。
 */
public interface MarketTradingScheduleSnapshotRepository {

    /**
     * 按 symbol 查询交易时间快照。
     *
     * @param symbol 品种代码
     * @return 交易时间快照
     */
    Optional<MarketTradingScheduleSnapshot> findBySymbol(String symbol);
}
