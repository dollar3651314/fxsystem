package com.falconx.market.repository.mapper;

import com.falconx.market.repository.mapper.record.MarketTradingSessionExceptionRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * 市场交易时间例外规则 MyBatis Mapper。
 */
@Mapper
public interface MarketTradingSessionExceptionMapper {

    /**
     * 查询全部交易时间例外规则。
     *
     * @return 例外规则记录
     */
    List<MarketTradingSessionExceptionRecord> selectAll();
}
