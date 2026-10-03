package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingHedgeLogRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 对冲观测日志 MyBatis Mapper。
 */
@Mapper
public interface TradingHedgeLogMapper {

    /**
     * 插入一条对冲观测日志。
     *
     * @param record 日志记录
     * @return 影响行数
     */
    int insertTradingHedgeLog(TradingHedgeLogRecord record);

    /**
     * 按 symbol 查询最近一条对冲观测日志。
     *
     * @param symbol 交易品种
     * @return 最近一条日志
     */
    TradingHedgeLogRecord selectLatestBySymbol(@Param("symbol") String symbol);
}
