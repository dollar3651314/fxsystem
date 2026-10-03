package com.falconx.market.repository;

import com.falconx.market.entity.MarketFeaturedSymbol;
import java.util.List;

/**
 * 跑马灯热门产品仓储。读全量有序；写全量替换。
 */
public interface MarketFeaturedSymbolRepository {

    /** 按 sort_order 升序的全部配置（含禁用）。 */
    List<MarketFeaturedSymbol> findAllOrdered();

    /**
     * 全量替换：清空后按列表顺序写入（sortOrder = 列表下标）。
     *
     * @param items 顺序即展示序；每项含 platformSymbol + enabled
     */
    void replaceAll(List<MarketFeaturedSymbol> items);
}
