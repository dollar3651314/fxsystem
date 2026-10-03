package com.falconx.market.repository.mapper;

import com.falconx.market.repository.mapper.record.MarketTradingHolidayRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * 市场节假日规则 MyBatis Mapper。
 */
@Mapper
public interface MarketTradingHolidayMapper {

    /**
     * 查询全部节假日规则。
     *
     * @return 节假日规则记录
     */
    List<MarketTradingHolidayRecord> selectAll();
}
