package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingHedgeLog;
import java.util.Optional;

/**
 * 对冲观测日志仓储接口。
 *
 * <p>该仓储负责把净敞口越阈值后的对冲告警事实落到 `t_hedge_log`，
 * 为后续人工排查或真实 A-book 接口对接保留可追溯记录。
 */
public interface TradingHedgeLogRepository {

    /**
     * 保存一条对冲告警日志。
     *
     * @param hedgeLog 对冲告警日志
     * @return 持久化后的日志
     */
    TradingHedgeLog save(TradingHedgeLog hedgeLog);

    /**
     * 按品种查询最近一次对冲观测日志。
     *
     * @param symbol 交易品种
     * @return 最近一条观测日志
     */
    Optional<TradingHedgeLog> findLatestBySymbol(String symbol);
}
