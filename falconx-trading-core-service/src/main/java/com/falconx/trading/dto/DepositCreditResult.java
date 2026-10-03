package com.falconx.trading.dto;

import com.falconx.trading.entity.TradingAccount;
import com.falconx.trading.entity.TradingDeposit;

/**
 * 入金入账结果。
 *
 * <p>该对象用于把应用层最终产物返回给测试或上层调用方，
 * 便于在不暴露 HTTP 接口的前提下验证“账户入账 + 业务入金事实 + 幂等结果”。
 */
public record DepositCreditResult(
        TradingDeposit deposit,
        TradingAccount account,
        boolean duplicate
) {
}
