package com.falconx.market.analytics.mapper;

import com.falconx.market.analytics.mapper.record.MarketQuoteTickRecord;
import com.falconx.market.repository.mapper.record.SymbolLastTickRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;

/**
 * ClickHouse 报价 Tick MyBatis Mapper。
 *
 * <p>该 Mapper 负责 `falconx_market_analytics.quote_tick` 的 SQL 声明。
 */
@Mapper
public interface MarketQuoteTickMapper {

    int insertQuoteTick(MarketQuoteTickRecord record);

    int insertQuoteTicks(List<MarketQuoteTickRecord> records);

    MarketQuoteTickRecord selectLatestBySymbol(@Param("symbol") String symbol);

    List<MarketQuoteTickRecord> selectRecentBySymbol(@Param("symbol") String symbol, @Param("limit") int limit);

    /**
     * 查指定 symbols 在 ClickHouse 上的最新 tick 时间。
     *
     * <p>STAGE-2-SYMBOL-PARAMS-DOWNSHIFT R4.1.B 新增：替代 t_symbol.last_tick_at 字段，
     * 由 console 主表 Tab 查询时调用 market internal RPC 拿一页 symbols 的最新 tick 时间。
     *
     * <p>没有 tick 的 symbol 不会出现在结果中。
     */
    List<SymbolLastTickRecord> selectLastTickBySymbols(@Param("symbols") List<String> symbols);
}
