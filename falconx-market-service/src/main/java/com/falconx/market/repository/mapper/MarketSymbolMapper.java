package com.falconx.market.repository.mapper;

import com.falconx.market.repository.mapper.record.MarketSymbolRecord;
import com.falconx.market.repository.mapper.record.MarketSymbolWithSpecRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 市场品种 MyBatis Mapper。
 */
@Mapper
public interface MarketSymbolMapper {

    /**
     * 查询全部可交易品种（按 LP 源 t_symbol，不含交易参数）。
     */
    List<MarketSymbolRecord> selectAllTradingSymbols();

    /**
     * 查询指定用户组可见的可交易 platform symbol（含 mapping 交易参数）。
     *
     * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT 后：JOIN `t_symbol_quote_mapping` 拿
     * 杠杆/费率/点差/qty 限制；symbol 字段为 mapping.platform_symbol。
     */
    List<MarketSymbolWithSpecRecord> selectTradingSymbolsByGroupCode(@Param("groupCode") String groupCode);

    /**
     * 按 source symbol 查询 LP 源元数据。
     */
    MarketSymbolRecord selectBySymbol(@Param("symbol") String symbol);

    /**
     * 按用户组可见性查询 platform symbol（含 mapping 交易参数）。
     */
    MarketSymbolWithSpecRecord selectVisibleTradingSymbol(@Param("symbol") String symbol,
                                                          @Param("groupCode") String groupCode);

    /**
     * 只追加不存在的 source symbol（LP 初始化路径）。
     */
    int insertIgnoreSymbols(@Param("records") List<MarketSymbolRecord> records);
}
