package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingRiskMarketConfigRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 跨品种集中度阈值配置 MyBatis Mapper。
 */
@Mapper
public interface TradingRiskMarketConfigMapper {

    /**
     * 按分类代码查询集中度阈值配置。
     */
    TradingRiskMarketConfigRecord selectByMarketCode(@Param("marketCode") String marketCode);

    /**
     * STAGE-2-RISK-ADMIN R4：管理端查询全部。
     */
    List<TradingRiskMarketConfigRecord> selectAll();

    /**
     * STAGE-2-RISK-ADMIN R4：管理端按 marketCode 编辑。
     */
    int updateAdminByMarketCode(@Param("marketCode") String marketCode,
                                @Param("concentrationThresholdUsd") BigDecimal concentrationThresholdUsd,
                                @Param("isEnabled") Integer isEnabled,
                                @Param("updatedAt") LocalDateTime updatedAt);
}
