package com.falconx.trading.repository;

import com.falconx.trading.entity.TradingRiskSwitch;
import java.util.List;
import java.util.Optional;

/**
 * 风控开关仓储接口。
 */
public interface TradingRiskSwitchRepository {

    Optional<TradingRiskSwitch> findByKey(String switchKey);

    List<TradingRiskSwitch> findAll();

    void upsert(TradingRiskSwitch riskSwitch);
}
