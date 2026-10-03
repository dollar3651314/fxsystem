package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingRiskConfig;
import java.util.List;
import java.util.Optional;

/**
 * 风控参数仓储接口。
 *
 * <p>该仓储负责按品种读取 owner 维护的 `t_risk_config`，
 * 供交易核心在下单后判断 B-book 净美元敞口是否已经超过对冲告警阈值。
 */
public interface TradingRiskConfigRepository {

    /**
     * 按品种查询风控参数。
     *
     * @param symbol 交易品种
     * @return 风控参数
     */
    Optional<TradingRiskConfig> findBySymbol(String symbol);

    /**
     * 按品种分类查询该分类下所有品种的 symbol 列表（跨品种集中度检查使用）。
     *
     * @param marketCode 品种分类
     * @return symbol 列表
     */
    List<String> findSymbolsByMarketCode(String marketCode);

    /**
     * STAGE-2-RISK-ADMIN R4：管理端分页查询。
     */
    List<TradingRiskConfig> findAdminPaginated(String symbol, String marketCode, int offset, int limit);

    /**
     * STAGE-2-RISK-ADMIN R4：管理端计数。
     */
    long countAdminFiltered(String symbol, String marketCode);

    /**
     * STAGE-2-RISK-ADMIN R4：新建。
     */
    void insertAdmin(TradingRiskConfig riskConfig);

    /**
     * STAGE-2-RISK-ADMIN R4：按 symbol 编辑（仅 4 字段）。
     */
    int updateAdminBySymbol(String symbol,
                            java.math.BigDecimal maxPositionPerUser,
                            java.math.BigDecimal maxPositionTotal,
                            Integer maxLeverage,
                            java.math.BigDecimal hedgeThresholdUsd);

    /**
     * STAGE-2-RISK-ADMIN R4：按 symbol 删除。
     */
    int deleteBySymbol(String symbol);

    /**
     * BBOOK-RISK-CONTROL-01：平台行（symbol IS NULL）。
     */
    Optional<TradingRiskConfig> findPlatformRow();

    /**
     * STAGE-14C1 Task 8：读平台行的 MarginLevel 阈值（stop_out_level / margin_call_level，小数口径）。
     *
     * @return 阈值（小数）；平台行缺失时为 {@link Optional#empty()}，caller 用默认 0.30/1.00 兜底
     */
    Optional<com.falconx.trading.service.model.MarginThresholds> findPlatformMarginThresholds();

    /**
     * STAGE-14D3a Task 2：读平台行（symbol IS NULL）冷静期秒数。
     *
     * @return 冷静期秒数；平台行缺失时为 {@link Optional#empty()}，caller 回退 properties 默认
     */
    Optional<Integer> findPlatformCoolingPeriodSeconds();

    /**
     * BBOOK-RISK-CONTROL-01：方向集中度配置更新。
     */
    int updateDirectionImbalance(String symbol,
                                 java.math.BigDecimal ratioThreshold,
                                 java.math.BigDecimal minTotalUsd);

    /**
     * BBOOK-RISK-CONTROL-01：平台 hedge_threshold_usd 更新。
     */
    int updatePlatformHedgeThreshold(java.math.BigDecimal hedgeThresholdUsd);

    /**
     * STAGE-14D3a Task 3：写平台行（symbol IS NULL）冷静期秒数。
     */
    void updatePlatformCoolingPeriodSeconds(int coolingPeriodSeconds);

    /**
     * STAGE-14D3a Task 3：写平台行（symbol IS NULL）的 MarginLevel 阈值（stop_out_level / margin_call_level，小数口径）。
     */
    void updatePlatformMarginThresholds(java.math.BigDecimal stopOutLevel, java.math.BigDecimal marginCallLevel);
}
