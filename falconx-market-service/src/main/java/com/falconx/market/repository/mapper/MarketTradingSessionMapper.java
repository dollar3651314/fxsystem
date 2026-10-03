package com.falconx.market.repository.mapper;

import com.falconx.market.repository.mapper.record.MarketTradingSessionRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * 市场周交易时段 MyBatis Mapper。
 */
@Mapper
public interface MarketTradingSessionMapper {

    /**
     * 查询全部启用的周交易时段。
     *
     * @return 周交易时段记录
     */
    List<MarketTradingSessionRecord> selectAllEnabled();
}
