package com.falconx.trading.entity;

import com.falconx.domain.enums.ChainType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 业务入金事实实体。
 *
 * <p>该对象对应 `falconx_trading.t_deposit` 的内存骨架表达，
 * 用于把 `wallet-service` 的原始链事实转换为交易域最终入账事实。
 *
 * @param depositId 业务入金主键
 * @param walletTxId wallet owner 产出的稳定原始交易主键
 * @param userId 用户主键
 * @param accountId 入账账户主键（REJECTED 状态下为 null）
 * @param chain 链类型
 * @param token 入金币种
 * @param txHash 链上交易哈希
 * @param amount 入账金额
 * @param status 当前业务入金状态
 * @param creditedAt 入账完成时间（REJECTED 时为 null）
 * @param reversedAt 反转完成时间；未反转则为空
 * @param rejectionReason 拒收原因，仅 REJECTED 状态有值
 * @param rejectedAt 拒收时间，仅 REJECTED 状态有值
 */
public record TradingDeposit(
        Long depositId,
        Long walletTxId,
        Long userId,
        Long accountId,
        ChainType chain,
        String token,
        String txHash,
        BigDecimal amount,
        TradingDepositStatus status,
        OffsetDateTime creditedAt,
        OffsetDateTime reversedAt,
        String rejectionReason,
        OffsetDateTime rejectedAt
) {
}
