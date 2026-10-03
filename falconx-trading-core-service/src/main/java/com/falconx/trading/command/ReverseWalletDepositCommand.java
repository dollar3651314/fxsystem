package com.falconx.trading.command;

import com.falconx.domain.enums.ChainType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 钱包入金回滚命令。
 *
 * <p>该命令由 `wallet.deposit.reversed` 事件消费入口构造，
 * 用于驱动交易域对已入账事实执行业务反转。
 */
public record ReverseWalletDepositCommand(
        String eventId,
        Long walletTxId,
        Long userId,
        ChainType chain,
        String token,
        String txHash,
        BigDecimal amount,
        OffsetDateTime reversedAt
) {
}
