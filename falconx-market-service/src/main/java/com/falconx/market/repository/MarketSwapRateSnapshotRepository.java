package com.falconx.market.repository;

import com.falconx.market.entity.MarketSwapRateSnapshot;
import java.util.Optional;

/**
 * 隔夜利息费率共享快照仓储。
 *
 * <p>该接口固定表达“market owner 写 Redis、其他服务只读”的共享快照语义。
 */
public interface MarketSwapRateSnapshotRepository {

    /**
     * 按 symbol 读取共享快照。
     *
     * @param symbol 品种代码
     * @return 快照可选结果
     */
    Optional<MarketSwapRateSnapshot> findBySymbol(String symbol);
}
