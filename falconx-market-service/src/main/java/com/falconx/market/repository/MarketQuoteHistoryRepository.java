package com.falconx.market.repository;

import com.falconx.market.entity.StandardQuote;
import java.util.List;
import java.util.Optional;

/**
 * 报价历史查询仓储。
 *
 * <p>当前实现回查 ClickHouse `quote_tick`，只用于参考价缓存冷启动回填，
 * 不进入交易热路径。
 */
public interface MarketQuoteHistoryRepository {

    /**
     * 查询指定品种最新一条历史报价。
     *
     * @param symbol 品种代码
     * @return 历史报价
     */
    Optional<StandardQuote> findLatestBySymbol(String symbol);

    /**
     * 查询指定品种最近的历史报价。
     *
     * @param symbol 品种代码
     * @param limit 返回数量
     * @return 按事件时间升序排列的历史报价
     */
    List<StandardQuote> findRecentBySymbol(String symbol, int limit);
}
