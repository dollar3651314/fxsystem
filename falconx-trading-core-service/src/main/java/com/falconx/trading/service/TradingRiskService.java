package com.falconx.trading.service;

import com.falconx.trading.command.PlaceMarketOrderCommand;
import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.service.model.TradingRiskDecision;

/**
 * 交易风险服务接口。
 *
 * <p>该服务负责承接下单前的同步风控校验，
 * 当前阶段只冻结价格时效、品种支持、杠杆范围和账户资金充足性检查。
 */
public interface TradingRiskService {

    /**
     * 校验市场单是否可接受。
     *
     * @param command 下单命令
     * @param account 交易账户
     * @param quote 最新行情快照
     * @return 风控决策结果
     */
    TradingRiskDecision evaluateMarketOrder(PlaceMarketOrderCommand command,
                                            TradingAccount account,
                                            TradingQuoteSnapshot quote);
}
