package com.falconx.market.repository.mapper;

import com.falconx.market.repository.mapper.record.MarketSymbolGroupMarkupRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户组加点 MyBatis Mapper。
 *
 * <p>承载启动期全量加载、增量刷新、管理端 CRUD 三大类查询。
 * trading-core 通过 internal RPC 间接消费这些数据，自身不直接读 MySQL。
 */
@Mapper
public interface MarketSymbolGroupMarkupMapper {

    /**
     * 查询全部 enabled=1 的组级加点配置。
     *
     * <p>用于 market-service 启动 / 刷新内存快照，以及 trading-core internal RPC 全量加载。
     *
     * @return 启用中的配置列表
     */
    List<MarketSymbolGroupMarkupRecord> selectAllEnabled();

    /**
     * 查询自指定时刻起更新过的全部配置，无论启用与否（trading-core 需要感知禁用）。
     *
     * @param since 自此时刻起更新过的配置才被返回
     * @return 增量更新列表
     */
    List<MarketSymbolGroupMarkupRecord> selectChangedSince(@Param("since") OffsetDateTime since);

    /**
     * 单点查询（管理端 / 删除前校验）。
     *
     * @param groupCode 用户组
     * @param platformSymbol 平台 symbol
     * @return 命中记录或空
     */
    MarketSymbolGroupMarkupRecord selectByPk(
            @Param("groupCode") String groupCode,
            @Param("platformSymbol") String platformSymbol);

    /**
     * 管理端分页过滤查询。
     *
     * @param groupCode 精确匹配，null 不过滤
     * @param symbolLike LIKE 匹配，null 不过滤
     * @param enabled 0/1 过滤，null 不过滤
     * @param offset 起始
     * @param limit 数量
     * @return 命中记录列表
     */
    List<MarketSymbolGroupMarkupRecord> selectByFilters(
            @Param("groupCode") String groupCode,
            @Param("symbolLike") String symbolLike,
            @Param("enabled") Integer enabled,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * 管理端分页统计。
     */
    long countByFilters(
            @Param("groupCode") String groupCode,
            @Param("symbolLike") String symbolLike,
            @Param("enabled") Integer enabled);

    /**
     * upsert 一条配置。
     *
     * @return 影响行数
     */
    int upsert(
            @Param("groupCode") String groupCode,
            @Param("platformSymbol") String platformSymbol,
            @Param("bidExtra") BigDecimal bidExtra,
            @Param("askExtra") BigDecimal askExtra,
            @Param("enabled") int enabled);

    /**
     * 删除一条配置。
     *
     * @return 影响行数
     */
    int delete(
            @Param("groupCode") String groupCode,
            @Param("platformSymbol") String platformSymbol);
}
