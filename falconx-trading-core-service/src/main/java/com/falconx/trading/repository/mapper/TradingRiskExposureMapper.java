package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingRiskExposureRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 品种净敞口 MyBatis Mapper。
 *
 * <p>该 Mapper 负责 `t_risk_exposure` 的增量更新和查询。
 */
@Mapper
public interface TradingRiskExposureMapper {

    /**
     * 对多头总量做增量更新。
     *
     * @param symbol 品种
     * @param quantity 增量数量
     * @param markPrice 最新标记价
     * @param updatedAt 更新时间
     * @return 影响行数
     */
    int applyLongDelta(@Param("symbol") String symbol,
                       @Param("quantity") BigDecimal quantity,
                       @Param("markPrice") BigDecimal markPrice,
                       @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 对空头总量做增量更新。
     *
     * @param symbol 品种
     * @param quantity 增量数量
     * @param markPrice 最新标记价
     * @param updatedAt 更新时间
     * @return 影响行数
     */
    int applyShortDelta(@Param("symbol") String symbol,
                        @Param("quantity") BigDecimal quantity,
                        @Param("markPrice") BigDecimal markPrice,
                        @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 对多头总量做回补。
     */
    int reduceLongDelta(@Param("symbol") String symbol,
                        @Param("quantity") BigDecimal quantity,
                        @Param("markPrice") BigDecimal markPrice,
                        @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 对空头总量做回补。
     */
    int reduceShortDelta(@Param("symbol") String symbol,
                         @Param("quantity") BigDecimal quantity,
                         @Param("markPrice") BigDecimal markPrice,
                         @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 仅按最新标记价刷新美元口径净敞口。
     */
    int refreshNetExposureUsd(@Param("symbol") String symbol,
                              @Param("bidPrice") BigDecimal bidPrice,
                              @Param("askPrice") BigDecimal askPrice,
                              @Param("fxRate") BigDecimal fxRate,
                              @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 按品种查询净敞口。
     *
     * @param symbol 品种
     * @return 敞口记录
     */
    TradingRiskExposureRecord selectBySymbol(@Param("symbol") String symbol);

    /**
     * 按多个品种批量查询净敞口。
     *
     * @param symbols 品种列表
     * @return 敞口记录列表
     */
    List<TradingRiskExposureRecord> selectBySymbols(@Param("symbols") List<String> symbols);

    /**
     * 按 market_code 汇总该分类所有品种的净美元敞口绝对值之和（跨品种集中度检查使用）。
     *
     * @param marketCode 品种分类
     * @return 净美元敞口绝对值之和；无数据时返回 0
     */
    java.math.BigDecimal sumAbsNetExposureUsdByMarketCode(@Param("marketCode") String marketCode);

    /**
     * STAGE-2-TRADING-MONITOR R4：管理端按 |net_exposure_usd| DESC 排序查询所有 symbol 敞口。
     *
     * @param symbol 可选筛选；为 null 时不限
     * @return 敞口记录列表
     */
    List<TradingRiskExposureRecord> selectAdminAll(@Param("symbol") String symbol);

    /**
     * BBOOK-RISK-CONTROL-01：平台总敞口（全 symbol 累计绝对值之和）。
     */
    java.math.BigDecimal sumAbsNetExposureUsdAllSymbols();
}
