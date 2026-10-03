package com.falconx.trading.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 业务入金 MyBatis 记录对象。
 *
 * <p>该记录对象对应 `t_deposit` 的数据库结构。
 *
 * @param id 主键 ID
 * @param walletTxId wallet owner 产出的稳定原始交易主键
 * @param userId 用户 ID
 * @param accountId 账户 ID
 * @param chain 链标识
 * @param token 代币符号
 * @param txHash 交易哈希
 * @param amount 入账金额
 * @param statusCode 状态码
 * @param creditedAt 入账时间
 * @param reversedAt 回滚时间
 * @param rejectionReason 拒收原因（仅 REJECTED 行）
 * @param rejectedAt 拒收时间（仅 REJECTED 行）
 * @param createdAt 创建时间
 */
public record TradingDepositRecord(
        Long id,
        Long walletTxId,
        Long userId,
        Long accountId,
        String chain,
        String token,
        String txHash,
        BigDecimal amount,
        Integer statusCode,
        LocalDateTime creditedAt,
        LocalDateTime reversedAt,
        String rejectionReason,
        LocalDateTime rejectedAt,
        LocalDateTime createdAt
) {
}
