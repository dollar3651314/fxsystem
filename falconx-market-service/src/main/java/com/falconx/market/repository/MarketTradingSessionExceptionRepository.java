package com.falconx.market.repository;

import com.falconx.market.entity.MarketTradingSessionException;
import java.util.List;

/**
 * 市场交易时间例外规则仓储。
 *
 * <p>该仓储负责读取 `t_trading_hours_exception`，
 * 供交易时间快照构建使用。
 */
public interface MarketTradingSessionExceptionRepository {

    /**
     * 查询全部交易时间例外规则。
     *
     * @return 例外规则列表
     */
    List<MarketTradingSessionException> findAll();
}
