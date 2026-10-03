package com.falconx.market.repository;

import com.falconx.market.entity.MarketTradingSession;
import java.util.List;

/**
 * 市场周交易时段仓储。
 *
 * <p>该仓储负责读取 `t_trading_hours`，
 * 供 Redis 交易时间快照构建使用。
 */
public interface MarketTradingSessionRepository {

    /**
     * 查询全部启用的交易时段规则。
     *
     * @return 交易时段规则列表
     */
    List<MarketTradingSession> findAllEnabled();
}
