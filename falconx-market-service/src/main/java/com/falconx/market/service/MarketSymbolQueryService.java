package com.falconx.market.service;

import com.falconx.market.dto.MarketSymbolsResponse;

/**
 * 市场品种列表查询服务。
 */
public interface MarketSymbolQueryService {

    /**
     * 查询首页展示用可交易品种列表。
     *
     * @param groupCode 用户组代码
     * @return 品种列表
     */
    MarketSymbolsResponse listTradingSymbols(String groupCode);
}
