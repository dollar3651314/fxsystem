package com.falconx.trading.repository;

import com.falconx.trading.service.model.TradingSwapRateSnapshot;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 隔夜利息共享快照读仓储。
 *
 * <p>`trading-core-service` 只通过该接口读取 market owner 写入的 Redis 快照，
 * 不跨服务访问 `falconx_market`。
 */
public interface TradingSwapRateSnapshotRepository {

    /**
     * 按 symbol 读取共享快照。
     *
     * @param symbol 品种代码
     * @return 快照可选结果
     */
    Optional<TradingSwapRateSnapshot> findBySymbol(String symbol);

    /**
     * 批量按 symbol 读取共享快照（MGET，单次往返）。
     * 缺失或反序列化失败的 symbol 不会出现在返回 Map 里。
     *
     * @param symbols 品种代码集合（去重后传入更高效）
     * @return symbol → snapshot 映射；空集合返回空 Map
     */
    Map<String, TradingSwapRateSnapshot> findBySymbols(Collection<String> symbols);
}
