package com.falconx.market.repository;

import com.falconx.market.entity.MarketSymbolGroupMarkup;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 用户组加点配置仓储。
 *
 * <p>承载启动期全量加载、增量刷新、管理端 CRUD。
 */
public interface MarketSymbolGroupMarkupRepository {

    /**
     * 启用中的全部配置。
     */
    List<MarketSymbolGroupMarkup> findAllEnabled();

    /**
     * 自指定时刻以来变更过的配置（含启用/禁用变化）。
     */
    List<MarketSymbolGroupMarkup> findChangedSince(OffsetDateTime since);

    /**
     * 单点查询。
     */
    Optional<MarketSymbolGroupMarkup> findByPk(String groupCode, String platformSymbol);

    /**
     * 管理端过滤查询。
     */
    List<MarketSymbolGroupMarkup> findByFilters(
            String groupCode, String symbolLike, Integer enabled, int offset, int limit);

    /**
     * 管理端统计。
     */
    long countByFilters(String groupCode, String symbolLike, Integer enabled);

    /**
     * upsert（POST / PUT 用）。
     *
     * @return 影响行数
     */
    int upsert(String groupCode, String platformSymbol,
               BigDecimal bidExtra, BigDecimal askExtra, int enabled);

    /**
     * 删除。
     *
     * @return 影响行数
     */
    int delete(String groupCode, String platformSymbol);
}
