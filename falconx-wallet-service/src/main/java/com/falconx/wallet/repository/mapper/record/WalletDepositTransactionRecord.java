package com.falconx.wallet.repository.mapper.record;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 原始链上入金 MyBatis 记录对象。
 *
 * @param id 主键 ID
 * @param userId 归属用户 ID
 * @param chain 链标识
 * @param token 代币符号
 * @param tokenContractAddress 代币合约地址
 * @param txHash 交易哈希
 * @param logIndex 日志索引
 * @param fromAddress 来源地址
 * @param toAddress 目标地址
 * @param amount 原始金额
 * @param blockNumber 区块高度
 * @param confirmations 当前确认数
 * @param requiredConfirmations 所需确认数
 * @param statusCode 状态码
 * @param detectedAt 检测时间
 * @param confirmedAt 确认时间
 * @param updatedAt 更新时间
 */
public record WalletDepositTransactionRecord(
        Long id,
        Long userId,
        String chain,
        String token,
        String tokenContractAddress,
        String txHash,
        Integer logIndex,
        String fromAddress,
        String toAddress,
        BigDecimal amount,
        Long blockNumber,
        Integer confirmations,
        Integer requiredConfirmations,
        Integer statusCode,
        LocalDateTime detectedAt,
        LocalDateTime confirmedAt,
        LocalDateTime updatedAt
) {
}
