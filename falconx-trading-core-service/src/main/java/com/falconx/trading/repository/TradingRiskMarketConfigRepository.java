package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingRiskMarketConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 跨品种集中度阈值配置仓储接口。
 */
public interface TradingRiskMarketConfigRepository {

    /**
     * 按品种分类代码查询集中度阈值配置。
     *
     * @param marketCode 品种分类（FX/CRYPTO/COMMODITY）
     * @return 集中度阈值配置
     */
    Optional<TradingRiskMarketConfig> findByMarketCode(String marketCode);

    /**
     * STAGE-2-RISK-ADMIN R4：管理端查询全部。
     */
    List<TradingRiskMarketConfig> findAll();

    /**
     * STAGE-2-RISK-ADMIN R4：管理端按 marketCode 编辑。
     *
     * @return 受影响行数
     */
    int updateAdminByMarketCode(String marketCode, BigDecimal concentrationThresholdUsd, boolean enabled);
}
