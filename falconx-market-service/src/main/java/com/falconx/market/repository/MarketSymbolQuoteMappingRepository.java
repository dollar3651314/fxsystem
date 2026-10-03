package com.falconx.market.repository;

import com.falconx.market.entity.MarketSymbolQuoteMapping;
import java.util.List;

/**
 * 平台 symbol 报价源映射仓储。
 */
public interface MarketSymbolQuoteMappingRepository {

    /**
     * 查询全部已启用、允许 LP 订阅且源 symbol 可用的映射。
     *
     * @return 映射列表
     */
    List<MarketSymbolQuoteMapping> findAllLpSubscribedMappings();
}
