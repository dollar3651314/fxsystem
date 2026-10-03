package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingSymbolCorrelationGroupRecord;
import com.falconx.trading.repository.mapper.record.TradingSymbolCorrelationMemberRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TradingSymbolCorrelationGroupMapper {

    /** 选所有 enabled=1 的组。 */
    List<TradingSymbolCorrelationGroupRecord> selectAllEnabled();

    /** 选所有 enabled=1 组的成员（用于内存 join）。 */
    List<TradingSymbolCorrelationMemberRecord> selectAllMembers();

    /** 按 symbol 反查 enabled=1 组（join member）。 */
    List<TradingSymbolCorrelationGroupRecord> selectGroupsBySymbol(@Param("symbol") String symbol);

    /** 按 groupCode 列表查所有成员。 */
    List<TradingSymbolCorrelationMemberRecord> selectMembersByGroupCodes(@Param("groupCodes") List<String> groupCodes);
}
