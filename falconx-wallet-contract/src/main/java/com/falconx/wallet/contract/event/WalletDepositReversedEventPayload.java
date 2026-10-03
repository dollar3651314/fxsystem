package com.falconx.wallet.contract.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.falconx.domain.enums.ChainType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * `falconx.wallet.deposit.reversed` 事件 payload 契约。
 *
 * <p>该事件表示原始链上入金因链回滚或业务撤销被判定失效。
 *
 * @param walletTxId wallet owner 产出的稳定原始交易主键
 * @param userId 归属用户 ID
 * @param chain 链类型
 * @param token 代币符号
 * @param txHash 链上交易哈希
 * @param fromAddress 来源地址
 * @param toAddress 目标地址
 * @param amount 链上原始金额
 * @param confirmations 当前确认数
 * @param requiredConfirmations 所需确认数
 * @param reversedAt 交易被判定回滚的时间
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletDepositReversedEventPayload(
        @NotNull Long walletTxId,
        @NotNull Long userId,
        @NotNull ChainType chain,
        @NotBlank String token,
        @NotBlank String txHash,
        @NotBlank String fromAddress,
        @NotBlank String toAddress,
        @NotNull BigDecimal amount,
        int confirmations,
        int requiredConfirmations,
        @NotNull OffsetDateTime reversedAt
) {
}
