package com.falconx.market.repository.mapper;

import com.falconx.market.repository.mapper.record.MarketSymbolQuoteMappingRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * symbol 报价源映射 MyBatis Mapper。
 */
@Mapper
public interface MarketSymbolQuoteMappingMapper {

    /**
     * 查询全部已启用、允许 LP 订阅且源 symbol 可用的报价源映射。
     *
     * @return 映射记录列表
     */
    List<MarketSymbolQuoteMappingRecord> selectAllLpSubscribedMappings();
}
