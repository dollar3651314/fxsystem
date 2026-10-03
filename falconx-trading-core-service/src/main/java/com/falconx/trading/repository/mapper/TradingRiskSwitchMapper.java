package com.falconx.trading.repository.mapper;

import com.falconx.trading.repository.mapper.record.TradingRiskSwitchRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 风控开关 Mapper（对应 `t_trading_risk_switch`）。
 */
@Mapper
public interface TradingRiskSwitchMapper {

    TradingRiskSwitchRecord selectByKey(@Param("switchKey") String switchKey);

    List<TradingRiskSwitchRecord> selectAll();

    int upsert(TradingRiskSwitchRecord record);
}
