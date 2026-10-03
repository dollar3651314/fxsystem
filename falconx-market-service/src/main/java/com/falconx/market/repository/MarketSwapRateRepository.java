package com.falconx.market.repository;

import com.falconx.market.entity.MarketSwapRate;
import java.util.List;

/**
 * 隔夜利息费率 owner 仓储。
 *
 * <p>该仓储负责读取 `t_swap_rate` 的正式 owner 数据，
 * 供 Redis 共享快照预热和后续结算链路使用。
 */
public interface MarketSwapRateRepository {

    /**
     * 查询全部费率规则。
     *
     * <p>结果按 `symbol / effective_from` 升序返回，
     * 便于上层直接按 symbol 聚合为完整历史快照。
     *
     * @return 全部费率规则
     */
    List<MarketSwapRate> findAllOrdered();
}
