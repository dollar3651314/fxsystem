package com.falconx.market.repository.mapper;

import com.falconx.market.repository.mapper.record.MarketSwapRateRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * 隔夜利息费率 MyBatis Mapper。
 *
 * <p>该 Mapper 只负责 `t_swap_rate` 的 SQL 声明。
 */
@Mapper
public interface MarketSwapRateMapper {

    /**
     * 查询全部费率规则。
     *
     * @return 规则列表
     */
    List<MarketSwapRateRecord> selectAllOrdered();
}
