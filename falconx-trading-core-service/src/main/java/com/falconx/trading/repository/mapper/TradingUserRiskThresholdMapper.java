package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingUserRiskThresholdRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * BBOOK-RISK-CONTROL-01：用户级风控阈值 Mapper。
 */
@Mapper
public interface TradingUserRiskThresholdMapper {

    TradingUserRiskThresholdRecord selectByUserId(@Param("userId") Long userId);

    List<TradingUserRiskThresholdRecord> selectAdminPaginated(@Param("userId") Long userId,
                                                              @Param("offset") int offset,
                                                              @Param("limit") int limit);

    long countAdminFiltered(@Param("userId") Long userId);

    /**
     * UPSERT：存在则更新，不存在则插入。
     */
    int upsert(@Param("userId") Long userId,
               @Param("netExposureThresholdUsd") BigDecimal netExposureThresholdUsd,
               @Param("profitableNetExposureThresholdUsd") BigDecimal profitableNetExposureThresholdUsd,
               @Param("isProfitableUser") Integer isProfitableUser,
               @Param("updatedBy") String updatedBy,
               @Param("updatedReason") String updatedReason,
               @Param("updatedAt") LocalDateTime updatedAt);

    int deleteByUserId(@Param("userId") Long userId);
}
