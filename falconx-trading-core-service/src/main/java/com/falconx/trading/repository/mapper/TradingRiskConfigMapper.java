package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingRiskConfigRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 风险阈值配置 MyBatis Mapper。
 */
@Mapper
public interface TradingRiskConfigMapper {

    /**
     * 按 symbol 查询风险配置。
     */
    TradingRiskConfigRecord selectBySymbol(@Param("symbol") String symbol);

    /**
     * 按 market_code 查询该分类下所有品种的 symbol 列表（跨品种集中度检查使用）。
     */
    List<String> selectSymbolsByMarketCode(@Param("marketCode") String marketCode);

    /**
     * STAGE-2-RISK-ADMIN R4：管理端分页查询。
     */
    List<TradingRiskConfigRecord> selectAdminPaginated(@Param("symbol") String symbol,
                                                       @Param("marketCode") String marketCode,
                                                       @Param("offset") int offset,
                                                       @Param("limit") int limit);

    /**
     * STAGE-2-RISK-ADMIN R4：管理端计数。
     */
    long countAdminFiltered(@Param("symbol") String symbol,
                            @Param("marketCode") String marketCode);

    /**
     * STAGE-2-RISK-ADMIN R4：插入。
     */
    int insertAdmin(TradingRiskConfigRecord record);

    /**
     * STAGE-2-RISK-ADMIN R4：按 symbol 编辑（仅 4 字段）。
     */
    int updateAdminBySymbol(@Param("symbol") String symbol,
                            @Param("maxPositionPerUser") java.math.BigDecimal maxPositionPerUser,
                            @Param("maxPositionTotal") java.math.BigDecimal maxPositionTotal,
                            @Param("maxLeverage") Integer maxLeverage,
                            @Param("hedgeThresholdUsd") java.math.BigDecimal hedgeThresholdUsd,
                            @Param("updatedAt") java.time.LocalDateTime updatedAt);

    /**
     * STAGE-2-RISK-ADMIN R4：按 symbol 删除。
     */
    int deleteBySymbol(@Param("symbol") String symbol);

    /**
     * BBOOK-RISK-CONTROL-01：平台行（symbol IS NULL）查询。
     */
    TradingRiskConfigRecord selectPlatformRow();

    /**
     * STAGE-14C1 Task 8：读平台行（symbol IS NULL）的 MarginLevel 阈值（V31 新增 2 列，小数口径）。
     *
     * <p>独立查询而非复用 {@link #selectPlatformRow()}，避免把 stop_out_level/margin_call_level
     * 灌进 {@code TradingRiskConfig} 实体（实体加列会波及全部构造点，留待后续按需）。
     * 平台行缺失 → 返回 {@code null}，Monitor 用默认 0.30/1.00 兜底。
     */
    com.falconx.trading.service.model.MarginThresholds selectPlatformMarginThresholds();

    /**
     * STAGE-14D3a Task 2：读平台行（symbol IS NULL）冷静期秒数；无行返回 null。
     */
    Integer selectPlatformCoolingPeriodSeconds();

    /**
     * BBOOK-RISK-CONTROL-01：方向集中度配置更新。
     */
    int updateDirectionImbalance(@Param("symbol") String symbol,
                                 @Param("ratioThreshold") java.math.BigDecimal ratioThreshold,
                                 @Param("minTotalUsd") java.math.BigDecimal minTotalUsd,
                                 @Param("updatedAt") java.time.LocalDateTime updatedAt);

    /**
     * BBOOK-RISK-CONTROL-01：平台 hedge_threshold_usd 更新（symbol IS NULL 行）。
     */
    int updatePlatformHedgeThreshold(@Param("hedgeThresholdUsd") java.math.BigDecimal hedgeThresholdUsd,
                                     @Param("updatedAt") java.time.LocalDateTime updatedAt);

    /**
     * STAGE-14D3a Task 3：写平台行（symbol IS NULL）冷静期秒数。
     */
    int updatePlatformCoolingPeriodSeconds(@Param("coolingPeriodSeconds") int coolingPeriodSeconds);

    /**
     * STAGE-14D3a Task 3：写平台行（symbol IS NULL）的 MarginLevel 阈值（stop_out_level / margin_call_level）。
     */
    int updatePlatformMarginThresholds(@Param("stopOutLevel") java.math.BigDecimal stopOutLevel,
                                       @Param("marginCallLevel") java.math.BigDecimal marginCallLevel);
}
