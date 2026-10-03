package com.falconx.market.service;

import com.falconx.market.entity.KlineSnapshot;
import java.util.List;

/**
 * K 线历史查询服务。
 */
public interface MarketKlineQueryService {

    List<KlineSnapshot> getRecentKlines(String symbol, String interval, Integer limit, String groupCode);
}
