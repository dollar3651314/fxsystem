package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingLiquidationLogRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 强平日志 MyBatis Mapper。
 */
@Mapper
public interface TradingLiquidationLogMapper {

    /**
     * 插入一条强平日志。
     *
     * @param record 强平日志记录
     * @return 影响行数
     */
    int insertTradingLiquidationLog(TradingLiquidationLogRecord record);

    List<TradingLiquidationLogRecord> selectByUserIdPaginated(@Param("userId") Long userId,
                                                              @Param("offset") int offset,
                                                              @Param("limit") int limit);

    long countByUserId(@Param("userId") Long userId);
}
