package com.falconx.trading.dto;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingDeposit;

/**
 * 入金反转结果。
 *
 * <p>该对象用于表达钱包回滚事件在交易域内的处理结果，
 * 既包含业务入金事实，也包含账户回退后的最新快照。
 */
public record DepositReversalResult(
        TradingDeposit deposit,
        TradingAccount account,
        boolean duplicate
) {
}
