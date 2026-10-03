package com.falconx.market.repository.mapper;

import com.falconx.market.repository.mapper.record.MarketFeaturedSymbolRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 跑马灯热门产品 MyBatis Mapper。
 *
 * <p>读：按 sort_order 升序全量（管理端含禁用 / 客户端经 service 过滤启用）。
 * 写：全量替换语义（deleteAll + batchInsert），由 service 在单事务内完成。
 */
@Mapper
public interface MarketFeaturedSymbolMapper {

    /** 按 sort_order 升序的全部配置（含禁用，供管理端）。 */
    List<MarketFeaturedSymbolRecord> selectAllOrdered();

    /** 清空全表（全量替换第一步）。 */
    int deleteAll();

    /** 批量插入（全量替换第二步）；sort_order 由 service 按列表序赋值。 */
    int batchInsert(@Param("items") List<MarketFeaturedSymbolRecord> items);
}
