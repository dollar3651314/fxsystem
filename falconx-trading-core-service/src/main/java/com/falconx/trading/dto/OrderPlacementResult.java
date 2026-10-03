package com.falconx.trading.dto;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingOrder;
import com.falconx.trading.entity.TradingPosition;
import com.falconx.trading.entity.TradingTrade;

/**
 * 下单结果 DTO。
 *
 * <p>该对象用于在当前无 REST 接口阶段承载应用层最小输出，
 * 包括订单、持仓、成交、账户快照和拒单原因。
 */
public record OrderPlacementResult(
        TradingOrder order,
        TradingPosition position,
        TradingTrade trade,
        TradingAccount account,
        boolean duplicate,
        String rejectionReason
) {
}
