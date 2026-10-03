package com.falconx.trading.command;

import com.falconx.domain.enums.ChainType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 钱包确认入金命令。
 *
 * <p>该命令由 `wallet.deposit.confirmed` 事件消费入口构造，
 * 用于把外部事件 payload 转换为 trading-core-service 应用层统一可消费的命令对象。
 */
public record CreditConfirmedDepositCommand(
        String eventId,
        Long walletTxId,
        Long userId,
        ChainType chain,
        String token,
        String txHash,
        BigDecimal amount,
        OffsetDateTime confirmedAt
) {
}
