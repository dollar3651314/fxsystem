package com.falconx.trading.command;

import java.math.BigDecimal;

/**
 * STAGE-7-WITHDRAW：提交出金命令对象。
 *
 * <p>由 controller 把 {@code X-User-Id} / {@code X-Idempotency-Key} 与 body 合并构造。
 */
public record SubmitWithdrawCommand(
        long userId,
        BigDecimal amount,
        String currency,
        String network,
        String targetAddress,
        long whitelistId,
        String idempotencyKey
) {
}
