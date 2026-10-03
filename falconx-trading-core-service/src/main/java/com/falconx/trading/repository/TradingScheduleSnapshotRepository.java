package com.falconx.trading.repository;

import com.falconx.trading.service.model.TradingScheduleSnapshot;
import java.util.Optional;

/**
 * 交易时间快照仓储。
 *
 * <p>该仓储只负责读取 `market-service` 写入 Redis 的交易时间快照，
 * 不暴露写入方法。写入路径由 `market-service` 独占，保证 TTL 一致性。
 */
public interface TradingScheduleSnapshotRepository {

    /**
     * 按 symbol 读取交易时间快照。
     *
     * @param symbol 品种代码
     * @return 交易时间快照
     */
    Optional<TradingScheduleSnapshot> findBySymbol(String symbol);
}
