package com.falconx.market.repository;

import com.falconx.market.entity.MarketTradingHoliday;
import java.util.List;

/**
 * 市场节假日规则仓储。
 *
 * <p>该仓储负责读取 `t_trading_holiday`，
 * 供交易时间快照构建使用。
 */
public interface MarketTradingHolidayRepository {

    /**
     * 查询全部节假日规则。
     *
     * @return 节假日规则列表
     */
    List<MarketTradingHoliday> findAll();
}
