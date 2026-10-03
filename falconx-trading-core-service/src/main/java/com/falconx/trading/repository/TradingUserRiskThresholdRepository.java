package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingUserRiskThreshold;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * BBOOK-RISK-CONTROL-01：用户级风控阈值仓储接口。
 */
public interface TradingUserRiskThresholdRepository {

    Optional<TradingUserRiskThreshold> findByUserId(Long userId);

    List<TradingUserRiskThreshold> findAdminPaginated(Long userId, int offset, int limit);

    long countAdminFiltered(Long userId);

    /**
     * UPSERT：阈值任一字段可为 null（表示不限制）；isProfitableUser 必填。
     */
    int upsert(Long userId,
               BigDecimal netExposureThresholdUsd,
               BigDecimal profitableNetExposureThresholdUsd,
               boolean isProfitableUser,
               String updatedBy,
               String updatedReason);

    int deleteByUserId(Long userId);
}
